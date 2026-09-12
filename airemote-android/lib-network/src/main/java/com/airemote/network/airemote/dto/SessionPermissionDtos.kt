package com.airemote.network.airemote.dto

import kotlinx.serialization.Serializable

/** airemote daemon wire 模型——Session 级权限模式与 grant。与 `airemote-daemon/src/types/api.ts` 对齐。 */

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
