package com.airemote.network.airemote.dto

import kotlinx.serialization.Serializable

/** airemote daemon wire 模型——agent（后端插件化 runtime）。与 `airemote-daemon/src/types/api.ts` 对齐。 */

/**
 * `GET /api/agents` 的一项（后端插件化 runtime）。
 *
 * [available] 是 daemon 探测 `--version` 的结果。默认 `true` 是刻意的：旧 daemon 不发这个
 * 字段，缺省成「不可用」会把所有 Agent 都置灰。
 */
@Serializable
data class AgentDto(
    val id: String,
    val name: String = "",
    val bin: String = "",
    val available: Boolean = true,
)

@Serializable
data class AgentsResponse(
    val agents: List<AgentDto> = emptyList(),
)
