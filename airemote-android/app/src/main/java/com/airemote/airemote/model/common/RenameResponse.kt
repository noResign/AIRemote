package com.airemote.airemote.model.common

import com.airemote.airemote.model.session.SessionDto
import kotlinx.serialization.Serializable

@Serializable
data class RenameResponse(
    val ok: Boolean = false,
    val session: SessionDto? = null,
)
