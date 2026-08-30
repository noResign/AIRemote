package com.airemote.airemote.model.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/sessions` / `GET /api/sessions/:id` 的会话对象。 */
@Serializable
data class SessionDto(
    val id: String,
    val runtime: String = "",
    val cwd: String = "",
    val title: String? = null,
    val createdAt: Long = 0,
    val lastActiveAt: Long = 0,
    val running: Boolean = false,
    val runningRunId: String? = null,
)

@Serializable
data class SessionsResponse(
    val sessions: List<SessionDto> = emptyList(),
)

/** 消息（`GET /api/sessions/:id` 返回，后端字段为 snake_case）。 */
@Serializable
data class MessageDto(
    val id: Long = 0,
    @SerialName("session_id") val sessionId: String = "",
    val role: String = "",
    val content: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class RunDto(
    val id: String,
    val sessionId: String = "",
    val runtime: String = "",
    val model: String? = null,
    val status: String = "",
    val prompt: String = "",
    val startedAt: Long = 0,
    val endedAt: Long? = null,
    val exitCode: Long? = null,
    val error: String? = null,
)

@Serializable
data class SessionDetailResponse(
    val session: SessionDto,
    val messages: List<MessageDto> = emptyList(),
    val runs: List<RunDto> = emptyList(),
)

/** `GET /api/runs`（当前运行中的 run 列表）。 */
@Serializable
data class RunsResponse(
    val runs: List<RunDto> = emptyList(),
)
