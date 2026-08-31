package com.airemote.airemote.model.claude

import kotlinx.serialization.Serializable

@Serializable
data class ClaudeSessionsResponse(
    val sessions: List<ClaudeSessionDto> = emptyList(),
)
