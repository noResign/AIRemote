package com.airemote.airemote.data.sse

import com.airemote.airemote.data.AiremoteClient
import com.airemote.airemote.model.event.ChatRequest
import com.airemote.airemote.model.event.SseFrame
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

sealed interface ChatStreamEvent {
    data class Frame(val frame: SseFrame) : ChatStreamEvent
    data class Failed(val message: String) : ChatStreamEvent
    data object Closed : ChatStreamEvent
}

/**
 * OkHttp-SSE based streaming for `/api/chat` and `/api/runs/:id/stream`.
 * Frames are parsed into [SseFrame] and surfaced as a [Flow].
 */
object AiremoteSse {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val sseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE stays open
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun chat(baseUrl: String, token: String, body: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val url = AiremoteClient.normalizeBaseUrl(baseUrl) + "api/chat"
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(ChatRequest.serializer(), body).toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer $token")
            .build()
        val source = EventSources.createFactory(sseClient).newEventSource(request, listener(this))
        awaitClose { source.cancel() }
    }

    fun streamRun(baseUrl: String, token: String, runId: String, after: Long? = null): Flow<ChatStreamEvent> = callbackFlow {
        val query = if (after != null) "?after=$after" else ""
        val url = AiremoteClient.normalizeBaseUrl(baseUrl) + "api/runs/$runId/stream$query"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        val source = EventSources.createFactory(sseClient).newEventSource(request, listener(this))
        awaitClose { source.cancel() }
    }

    private fun listener(scope: ProducerScope<ChatStreamEvent>): EventSourceListener =
        object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val frame = try {
                    json.decodeFromString(SseFrame.serializer(), data)
                } catch (_: Exception) {
                    null
                }
                if (frame != null) scope.trySend(ChatStreamEvent.Frame(frame))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val detail = when {
                    response?.code == 401 -> "token 无效（401）"
                    response != null -> "HTTP ${response.code}"
                    t != null -> t.message ?: "SSE 连接失败"
                    else -> "SSE 连接失败"
                }
                scope.trySend(ChatStreamEvent.Failed(detail))
                scope.close()
            }

            override fun onClosed(eventSource: EventSource) {
                scope.trySend(ChatStreamEvent.Closed)
                scope.close()
            }
        }
}
