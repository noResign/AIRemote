package com.airemote.airemote.model.session

import kotlinx.serialization.Serializable

/** `GET /api/runs`（当前运行中的 run 列表）。 */
@Serializable
data class RunsResponse(
    val runs: List<RunDto> = emptyList(),
)
