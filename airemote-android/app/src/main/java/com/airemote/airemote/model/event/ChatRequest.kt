package com.airemote.airemote.model.event

import kotlinx.serialization.Serializable

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
