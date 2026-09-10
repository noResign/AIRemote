package com.airemote.network.llm

import com.airemote.network.llm.dto.LlmRequest
import com.airemote.network.llm.dto.LlmResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * LLM 供应商抽象。上层业务只依赖它，与具体厂商解耦；换厂商只加一个实现 + 路由注册。
 *
 * 对应 Java 版 `module-ai` 的 `LlmProvider`，但把回调式的 `LlmStreamHandler` 换成 `Flow`
 * （可取消、可组合、天然背压）。
 */
interface LlmProvider {

    /** 流式调用（OpenAI 兼容 SSE，`stream = true`）。失败抛 [LlmException]。 */
    fun stream(request: LlmRequest): Flow<LlmStreamEvent>

    /**
     * 便捷：把整条流聚合成一个完整响应。
     * 默认实现累加 `ContentDelta` / `ReasoningDelta`，并以 `Completed` 的聚合结果为基底。
     */
    suspend fun collectStream(request: LlmRequest): LlmResponse {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var completed: LlmResponse? = null
        stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.ContentDelta -> content.append(event.delta)
                is LlmStreamEvent.ReasoningDelta -> reasoning.append(event.delta)
                is LlmStreamEvent.Completed -> completed = event.response
            }
        }
        val response = completed ?: LlmResponse()
        if (content.isNotEmpty()) response.content = content.toString()
        if (reasoning.isNotEmpty()) response.reasoningContent = reasoning.toString()
        return response
    }

    /** 非流式调用（`stream = false`），部分厂商在非流式下 tool_calls 更完整。失败抛 [LlmException]。 */
    suspend fun generate(request: LlmRequest): LlmResponse
}
