package com.airemote.airemote.model.session

import kotlinx.serialization.Serializable

@Serializable
data class SessionsResponse(
    val sessions: List<SessionDto> = emptyList(),
)
