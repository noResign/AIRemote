package com.airemote.network.airemote.dto

import kotlinx.serialization.Serializable

/** airemote daemon wire 模型——本机 Claude 会话（可导入续接）。与 `airemote-daemon/src/types/api.ts` 对齐。 */

/** `GET /api/claude-sessions` 的一项（本机 Claude 会话，可导入续接）。 */
@Serializable
data class ClaudeSessionDto(
    val sessionId: String,
    val cwd: String = "",
    val summary: String = "",
    val messageCount: Int = 0,
    val lastActiveAt: Long = 0,
)

@Serializable
data class ClaudeSessionsResponse(
    val sessions: List<ClaudeSessionDto> = emptyList(),
)
