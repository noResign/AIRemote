package com.airemote.airemote.model.event

import kotlinx.serialization.Serializable

@Serializable
data class SseFrame(
    val runId: String,
    val seq: Long,
    val event: NormalizedEvent,
)
