package com.noresign.paboot.network.llm

import com.noresign.paboot.network.llm.dto.LlmRequest
import com.noresign.paboot.network.llm.dto.LlmResponse
import com.noresign.paboot.network.llm.dto.ToolCall
import com.noresign.paboot.network.sse.OkHttpSseSource
import com.noresign.paboot.network.sse.SseException
import com.noresign.paboot.network.sse.SseSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val DONE_SENTINEL = "[DONE]"
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

/**
 * **OpenAI 兼容**的 LLM 供应商实现——凡遵循 `POST {baseUrl}/chat/completions` 的服务都能用
 * （DeepSeek、OpenAI、火山方舟等）。
 *
 * 职责边界：只负责「造请求 + 解 chunk + 聚合」，传输复用通用 [SseSource]。
 * `[DONE]` 终止符、`reasoning_content` 思维链、流式 `tool_calls` 按 index 累积等细节都在这里处理。
 *
 * 三个注入点：流式走 [sse]（长连接，读超时由它自己管），非流式走 [httpClient]（短连接），
 * [json] 用于解码——默认实现都可用，测试时换成假的即可。
 */
class OpenAiCompatLlmProvider(
    private val config: OpenAiCompatConfig,
    private val sse: SseSource = OkHttpSseSource(),
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = DEFAULT_JSON,
) : LlmProvider {

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        // 显式持有下游 collector：collect 内 emit 时 receiver 不会歧义
        val downstream: FlowCollector<LlmStreamEvent> = this

        val aggregate = LlmResponse(model = request.model ?: config.defaultModel)
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolIds = LinkedHashMap<Int, String>()
        val toolNames = LinkedHashMap<Int, String>()
        val toolArgs = LinkedHashMap<Int, StringBuilder>()
        var finishReason: String? = null

        try {
            sse.events(httpRequest(request, stream = true)).collect { event ->
                val payload = event.data
                if (payload != DONE_SENTINEL) {
                    val chunk = decodeChunk(payload)
                    if (chunk != null) {
                        chunk.model?.let { aggregate.model = it }
                        chunk.usage?.let { applyUsage(aggregate, it) }
                        val choice = chunk.choices.firstOrNull()
                        if (choice != null) {
                            val text = choice.delta.content
                            if (!text.isNullOrEmpty()) {
                                content.append(text)
                                downstream.emit(LlmStreamEvent.ContentDelta(text))
                            }
                            val trace = choice.delta.reasoningContent
                            if (!trace.isNullOrEmpty()) {
                                reasoning.append(trace)
                                downstream.emit(LlmStreamEvent.ReasoningDelta(trace))
                            }
                            choice.delta.toolCalls?.forEach { delta ->
                                accumulateToolCall(delta, toolIds, toolNames, toolArgs)
                            }
                            val reason = choice.finishReason
                            if (!reason.isNullOrEmpty()) finishReason = reason
                        }
                    }
                }
            }
        } catch (e: SseException) {
            throw LlmException(e.httpCode, e.message ?: "LLM 流式请求失败", e)
        }

        aggregate.content = content.toString()
        aggregate.reasoningContent = reasoning.toString().ifEmpty { null }
        aggregate.finishReason = finishReason
        aggregate.toolCalls = buildToolCalls(toolIds, toolNames, toolArgs)
        downstream.emit(LlmStreamEvent.Completed(aggregate))
    }

    override suspend fun generate(request: LlmRequest): LlmResponse = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(httpRequest(request, stream = false)).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException(response.code, "HTTP ${response.code}: ${body.take(300)}")
                }
                decodeCompletion(body)
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            throw LlmException(httpCode = null, message = e.message ?: "LLM 非流式请求失败", cause = e)
        }
    }

    private fun httpRequest(request: LlmRequest, stream: Boolean): Request {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val payload = buildRequestBody(request, stream).toString().toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(url)
            .post(payload)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
        config.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun buildRequestBody(request: LlmRequest, stream: Boolean): JsonObject = buildJsonObject {
        put("model", request.model ?: config.defaultModel ?: "")
        put("stream", stream)
        putJsonArray("messages") {
            request.messages.forEach { message ->
                addJsonObject {
                    put("role", message.role)
                    message.content?.let { put("content", it) }
                    message.reasoningContent?.let { put("reasoning_content", it) }
                    message.toolCallId?.let { put("tool_call_id", it) }
                    message.toolCalls?.let { calls ->
                        putJsonArray("tool_calls") {
                            calls.forEach { call ->
                                addJsonObject {
                                    put("id", call.id)
                                    put("type", call.type)
                                    putJsonObject("function") {
                                        put("name", call.function.name)
                                        put("arguments", call.function.arguments)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        request.options?.let { options ->
            options.temperature?.let { put("temperature", it) }
            options.maxOutputTokens?.let { put("max_tokens", it) }
            options.thinkingType?.let { type -> putJsonObject("thinking") { put("type", type) } }
            options.responseFormat?.let { format -> putJsonObject("response_format") { put("type", format) } }
            options.extra.forEach { (key, value) -> put(key, value) }
        }
        request.tools?.let { put("tools", it) }
        request.toolChoice?.let { choice ->
            if (choice == "auto" || choice == "none") {
                put("tool_choice", choice)
            } else {
                putJsonObject("tool_choice") {
                    put("type", "function")
                    putJsonObject("function") { put("name", choice) }
                }
            }
        }
    }

    private fun decodeChunk(payload: String): OpenAiChunk? = try {
        json.decodeFromString(OpenAiChunk.serializer(), payload)
    } catch (_: Exception) {
        null // 非 JSON / 非本协议帧（例如心跳），忽略
    }

    private fun decodeCompletion(payload: String): LlmResponse {
        val root = try {
            json.decodeFromString(OpenAiCompletion.serializer(), payload)
        } catch (e: Exception) {
            throw LlmException(httpCode = null, message = "响应解析失败：${e.message}", cause = e)
        }
        val choice = root.choices.firstOrNull()
        return LlmResponse(
            content = choice?.message?.content.orEmpty(),
            reasoningContent = choice?.message?.reasoningContent,
            finishReason = choice?.finishReason,
            model = root.model,
            promptTokens = root.usage?.promptTokens,
            completionTokens = root.usage?.completionTokens,
            totalTokens = root.usage?.totalTokens,
            toolCalls = choice?.message?.toolCalls,
        )
    }

    private fun applyUsage(response: LlmResponse, usage: OpenAiUsage) {
        usage.promptTokens?.let { response.promptTokens = it }
        usage.completionTokens?.let { response.completionTokens = it }
        usage.totalTokens?.let { response.totalTokens = it }
    }

    private fun accumulateToolCall(
        delta: OpenAiChunk.ToolCallDelta,
        ids: MutableMap<Int, String>,
        names: MutableMap<Int, String>,
        args: MutableMap<Int, StringBuilder>,
    ) {
        delta.id?.let { ids[delta.index] = it }
        delta.function?.name?.let { names[delta.index] = it }
        delta.function?.arguments?.let { piece ->
            args.getOrPut(delta.index) { StringBuilder() }.append(piece)
        }
    }

    private fun buildToolCalls(
        ids: Map<Int, String>,
        names: Map<Int, String>,
        args: Map<Int, StringBuilder>,
    ): List<ToolCall>? {
        if (names.isEmpty()) return null
        return names.keys.sorted().map { index ->
            ToolCall(
                id = ids[index] ?: "call_$index",
                function = ToolCall.Function(
                    name = names[index].orEmpty(),
                    arguments = args[index]?.toString() ?: "{}",
                ),
            )
        }
    }
}

private val sharedHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // 非流式：等完整响应
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

/** [OpenAiCompatLlmProvider] 非流式请求用的默认客户端；构造时可注入替换。 */
fun defaultHttpClient(): OkHttpClient = sharedHttpClient
