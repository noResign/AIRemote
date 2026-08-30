package com.airemote.airemote.model.common

import com.airemote.airemote.model.session.SessionDto
import kotlinx.serialization.Serializable

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
