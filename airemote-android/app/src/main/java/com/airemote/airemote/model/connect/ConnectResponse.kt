package com.airemote.airemote.model.connect

import kotlinx.serialization.Serializable

/** `GET /api/health` — unauthenticated reachability probe. */
@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val service: String? = null,
    val version: String? = null,
)

/**
 * Combined result of a connectivity check: the daemon is reachable (health ok)
 * and the token is valid (sessions list was fetched).
 */
data class ConnectResponse(
    val service: String,
    val version: String,
    val sessionCount: Int,
)
