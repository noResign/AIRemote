package com.airemote.network.airemote.dto

import kotlinx.serialization.Serializable

/** airemote daemon wire 模型——Workspace 与目录选择器。与 `airemote-daemon/src/types/api.ts` 对齐。 */

@Serializable
data class WorkspaceDto(
    val id: String,
    val name: String = "",
    val path: String = "",
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val sessionCount: Int = 0,
    val createdAt: Long = 0,
    val lastUsedAt: Long = 0,
)

@Serializable
data class WorkspacesResponse(
    val workspaces: List<WorkspaceDto> = emptyList(),
)

@Serializable
data class CreateWorkspaceRequest(
    val name: String? = null,
    val path: String,
)

@Serializable
data class UpdateWorkspaceRequest(
    val name: String? = null,
    val isDefault: Boolean? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class WorkspaceResponse(
    val ok: Boolean = false,
    val workspace: WorkspaceDto? = null,
)

@Serializable
data class DirectoryEntryDto(
    val name: String,
    val path: String,
    val isWorkspace: Boolean = false,
)

@Serializable
data class DirectoriesResponse(
    val path: String = "",
    val parent: String? = null,
    val entries: List<DirectoryEntryDto> = emptyList(),
)
