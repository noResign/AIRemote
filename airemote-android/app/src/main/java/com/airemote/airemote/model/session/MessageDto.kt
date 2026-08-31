package com.airemote.airemote.model.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 消息（`GET /api/sessions/:id` 返回，后端字段为 snake_case）。 */
@Serializable
data class MessageDto(
    val id: Long = 0,
    @SerialName("session_id") val sessionId: String = "",
    val role: String = "",
    val content: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)
