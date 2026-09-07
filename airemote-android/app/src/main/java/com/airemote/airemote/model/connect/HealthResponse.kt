package com.airemote.airemote.model.connect

import kotlinx.serialization.Serializable

/** `GET /api/health` — unauthenticated reachability probe. */
@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val service: String? = null,
    val version: String? = null,
    /** daemon 主 workspace 根目录（用于会话列表默认展开当前 workspace）。 */
    val workspace: String? = null,
)
