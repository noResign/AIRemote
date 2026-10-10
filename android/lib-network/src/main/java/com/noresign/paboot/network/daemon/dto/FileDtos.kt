package com.noresign.paboot.network.daemon.dto

import kotlinx.serialization.Serializable

/** paboot daemon wire 模型——文件 Tab 的全部文件浏览。与 `daemon/src/types/api.ts` 对齐。 */

@Serializable
data class FileEntryDto(
    val name: String,
    val path: String,
    val type: String = "file",
    val size: Long? = null,
    val modifiedAt: Long? = null,
)

@Serializable
data class FilesResponse(
    val workspaceId: String = "",
    /** 工作区主目录，用于展示。 */
    val workspacePath: String = "",
    /** 实际列出的目录树；未传 `root` 时等于 `workspacePath`。 */
    val root: String = "",
    /** 该工作区的全部根（主目录在前，其后是附加目录）；用于渲染根切换器。 */
    val roots: List<String> = emptyList(),
    /** 浏览书签（不属于 roots，不授予 agent 权限），同样作为可切换 tab 渲染。 */
    val shortcutDirs: List<String> = emptyList(),
    val path: String = "",
    val parent: String? = null,
    val entries: List<FileEntryDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class FileContentDto(
    /** `path` 相对哪个目录树。 */
    val root: String = "",
    val path: String = "",
    val size: Long = 0,
    val binary: Boolean = false,
    val truncated: Boolean = false,
    val content: String = "",
)
