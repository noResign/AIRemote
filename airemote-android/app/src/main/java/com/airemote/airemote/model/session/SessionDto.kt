package com.airemote.airemote.model.session

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
