package com.airemote.airemote.model.workspace

import kotlinx.serialization.Serializable

/** `GET /api/workspaces` —— 允许的工作目录白名单。 */
@Serializable
data class WorkspacesResponse(
    val workspaces: List<String> = emptyList(),
    val default: String = "",
)
