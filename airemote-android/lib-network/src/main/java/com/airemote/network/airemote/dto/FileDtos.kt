package com.airemote.network.airemote.dto

import kotlinx.serialization.Serializable

/** airemote daemon wire 模型——文件 Tab 的全部文件浏览。与 `airemote-daemon/src/types/api.ts` 对齐。 */

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
