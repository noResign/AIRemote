package com.airemote.airemote.model.event

import kotlinx.serialization.Serializable

/** `GET /api/runs/:id/events` 回放响应。 */
@Serializable
data class EventsResponse(
    val runId: String,
    val events: List<SseFrame> = emptyList(),
)
