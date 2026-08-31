package com.airemote.airemote.model.session

import kotlinx.serialization.Serializable

@Serializable
data class RunDto(
    val id: String,
    val sessionId: String = "",
    val runtime: String = "",
    val model: String? = null,
    val status: String = "",
    val prompt: String = "",
    val startedAt: Long = 0,
    val endedAt: Long? = null,
    val exitCode: Long? = null,
    val error: String? = null,
)
