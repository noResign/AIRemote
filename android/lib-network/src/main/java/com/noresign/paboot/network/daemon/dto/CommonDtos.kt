package com.noresign.paboot.network.daemon.dto

import kotlinx.serialization.Serializable

/** paboot daemon wire 模型——健康探测与通用请求/响应。与 `daemon/src/types/api.ts` 对齐。 */

/** `GET /api/health` —— 无需鉴权的可达性探测。 */
@Serializable
data class HealthResponse(
    val ok: Boolean = false,
    val service: String? = null,
    val version: String? = null,
    /** daemon 主 workspace 根目录（用于会话列表默认展开当前 workspace）。 */
    val workspace: String? = null,
)

/** 通用 `{ok, ...}` 响应（删除/取消/审批等）。 */
@Serializable
data class OkResponse(
    val ok: Boolean = false,
)

@Serializable
data class RenameRequest(
    val title: String,
)

@Serializable
data class RenameResponse(
    val ok: Boolean = false,
    val session: SessionDto? = null,
)
