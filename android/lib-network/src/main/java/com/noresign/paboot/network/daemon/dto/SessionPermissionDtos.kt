package com.noresign.paboot.network.daemon.dto

import kotlinx.serialization.Serializable

/** paboot daemon wire 模型——Session 级权限模式与 grant。与 `daemon/src/types/api.ts` 对齐。 */

@Serializable
data class PermissionGrantDto(
    val toolName: String,
    val createdAt: Long = 0,
)

@Serializable
data class SessionPermissionsResponse(
    val mode: String = "ask",
    val grants: List<PermissionGrantDto> = emptyList(),
)

@Serializable
data class UpdateSessionPermissionsRequest(
    val mode: String,
)
