package com.airemote.airemote.model.common

import kotlinx.serialization.Serializable

/** 通用 `{ok, ...}` 响应（删除/取消/审批等）。 */
@Serializable
data class OkResponse(
    val ok: Boolean = false,
)
