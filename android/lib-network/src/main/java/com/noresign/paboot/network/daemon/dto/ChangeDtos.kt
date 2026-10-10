package com.noresign.paboot.network.daemon.dto

import kotlinx.serialization.Serializable

/** paboot daemon wire 模型——Git 改动列表与 diff。与 `daemon/src/types/api.ts` 对齐。 */

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
    /** 工作区主目录，用于展示。 */
    val workspacePath: String = "",
    /** 实际检查的目录树；未传 `root` 时等于 `workspacePath`。 */
    val root: String = "",
    /** 该工作区的全部根（主目录在前，其后是附加目录）；用于渲染根切换器。 */
    val roots: List<String> = emptyList(),
    /** 浏览书签（不属于 roots，不授予 agent 权限），同样作为可切换 tab 渲染。 */
    val shortcutDirs: List<String> = emptyList(),
    val isGitRepo: Boolean = false,
    val gitRoot: String? = null,
    /** `root` 直接子目录里的 git 仓库（`root` 相对路径），仅在非仓库目录下有值。 */
    val repos: List<String> = emptyList(),
    val files: List<ChangedFileDto> = emptyList(),
)

@Serializable
data class DiffResponse(
    /** `path` 相对哪个目录树。 */
    val root: String = "",
    val path: String = "",
    val oldPath: String? = null,
    val status: String = "modified",
    val binary: Boolean = false,
    val truncated: Boolean = false,
    val patch: String = "",
)
