package com.airemote.airemote.model.connect

import kotlinx.serialization.Serializable

/** `GET /api/health` — unauthenticated reachability probe. */
@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val service: String? = null,
    val version: String? = null,
)
