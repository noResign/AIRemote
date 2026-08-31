package com.airemote.airemote.model.common

import kotlinx.serialization.Serializable

@Serializable
data class RenameRequest(
    val title: String,
)
