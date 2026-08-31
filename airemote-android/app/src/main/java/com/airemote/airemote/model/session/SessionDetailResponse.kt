package com.airemote.airemote.model.session

import kotlinx.serialization.Serializable

@Serializable
data class SessionDetailResponse(
    val session: SessionDto,
    val messages: List<MessageDto> = emptyList(),
    val runs: List<RunDto> = emptyList(),
)
