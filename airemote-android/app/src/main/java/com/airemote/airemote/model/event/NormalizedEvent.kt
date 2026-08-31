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
        val ttftMs: Double? = null,
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
        val durationMs: Double? = null,
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
