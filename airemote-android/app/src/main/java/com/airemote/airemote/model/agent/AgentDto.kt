package com.airemote.airemote.model.agent

import kotlinx.serialization.Serializable

/** `GET /api/agents` 的一项（后端插件化 runtime，当前 `claude`）。 */
@Serializable
data class AgentDto(
    val id: String,
    val name: String = "",
    val bin: String = "",
)
