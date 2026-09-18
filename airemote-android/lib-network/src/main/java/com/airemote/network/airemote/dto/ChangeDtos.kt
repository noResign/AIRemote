package com.airemote.network.airemote.dto

import kotlinx.serialization.Serializable

/** airemote daemon wire 模型——Git 改动列表与 diff。与 `airemote-daemon/src/types/api.ts` 对齐。 */

@Serializable
data class ChangedFileDto(
    val path: String,
    val oldPath: String? = null,
    val status: String = "modified",
    val staged: Boolean = false,
    val binary: Boolean = false,
    val isDirectory: Boolean = false,
    val additions: Int? = null,
    val deletions: Int? = null,
)

@Serializable
data class ChangesResponse(
    val workspaceId: String = "",
    val workspacePath: String = "",
    /** 本次检查的目录，workspace 相对路径（"" = 工作区根目录）。 */
    val dir: String = "",
    val isGitRepo: Boolean = false,
    val gitRoot: String? = null,
    /** `dir` 直接子目录里的 git 仓库（workspace 相对路径），仅在非仓库目录下有值。 */
    val repos: List<String> = emptyList(),
    val files: List<ChangedFileDto> = emptyList(),
)

@Serializable
data class DiffResponse(
    val path: String = "",
    val oldPath: String? = null,
    val status: String = "modified",
    val binary: Boolean = false,
    val truncated: Boolean = false,
    val patch: String = "",
)
