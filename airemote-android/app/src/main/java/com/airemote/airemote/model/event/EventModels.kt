package com.airemote.airemote.model.event

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 后端归一化事件流（runtime 无关）。SSE 的 `data` 里是 `{runId, seq, event}`，
 * `event` 就是本 sealed class，以 `type` 字段作为多态判别。
 */
@Serializable
sealed class NormalizedEvent {

    @Serializable
    @SerialName("status")
    data class Status(
        val label: String,
        val model: String? = null,
        val sessionId: String? = null,
        val runtime: String? = null,
        val ttftMs: Long? = null,
        val terminal: Boolean? = null,
    ) : NormalizedEvent()

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val delta: String) : NormalizedEvent()

    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(val delta: String) : NormalizedEvent()

    @Serializable
    @SerialName("thinking_start")
    object ThinkingStart : NormalizedEvent()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonElement? = null) : NormalizedEvent()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolUseId: String? = null,
        val content: String = "",
        val isError: Boolean? = null,
    ) : NormalizedEvent()

    @Serializable
    @SerialName("usage")
    data class Usage(
        val usage: JsonElement? = null,
        val costUsd: Double? = null,
        val durationMs: Long? = null,
        val stopReason: String? = null,
        val isError: Boolean? = null,
    ) : NormalizedEvent()

    @Serializable
    @SerialName("turn_end")
    data class TurnEnd(val stopReason: String) : NormalizedEvent()

    @Serializable
    @SerialName("error")
    data class Error(val code: String? = null, val message: String, val terminal: Boolean? = null) : NormalizedEvent()

    @Serializable
    @SerialName("permission_request")
    data class PermissionRequest(
        val permissionId: String,
        val toolName: String,
        val toolInput: JsonElement? = null,
    ) : NormalizedEvent()
}

@Serializable
data class SseFrame(
    val runId: String,
    val seq: Long,
    val event: NormalizedEvent,
)

/** `GET /api/runs/:id/events` 回放响应。 */
@Serializable
data class EventsResponse(
    val runId: String,
    val events: List<SseFrame> = emptyList(),
)

/** `POST /api/chat` 请求体。 */
@Serializable
data class ChatRequest(
    val prompt: String,
    val sessionId: String? = null,
    val claudeSessionId: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val cwd: String? = null,
)

/** `POST /api/permissions/:id/decision` 请求体。 */
@Serializable
data class PermissionDecisionRequest(
    val decision: String,
    val reason: String? = null,
)
