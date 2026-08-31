package com.airemote.airemote.model.event

import kotlinx.serialization.Serializable

/** `POST /api/permissions/:id/decision` 请求体。 */
@Serializable
data class PermissionDecisionRequest(
    val decision: String,
    val reason: String? = null,
)
