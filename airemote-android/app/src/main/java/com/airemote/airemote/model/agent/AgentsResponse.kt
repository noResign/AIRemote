package com.airemote.airemote.model.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentsResponse(
    val agents: List<AgentDto> = emptyList(),
)
