package com.airemote.network.airemote.dto

import kotlinx.serialization.Serializable

/** airemote daemon wire 模型——agent（后端插件化 runtime）。与 `airemote-daemon/src/types/api.ts` 对齐。 */

/** `GET /api/agents` 的一项（后端插件化 runtime，当前 `claude`）。 */
@Serializable
data class AgentDto(
    val id: String,
    val name: String = "",
    val bin: String = "",
)

@Serializable
data class AgentsResponse(
    val agents: List<AgentDto> = emptyList(),
)
