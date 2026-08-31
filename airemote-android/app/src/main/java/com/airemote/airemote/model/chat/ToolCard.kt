package com.airemote.airemote.model.chat

import kotlinx.serialization.json.JsonElement

data class ToolCard(
    val id: String,
    val name: String,
    val input: JsonElement? = null,
    val result: String? = null,
    val isError: Boolean = false,
    val running: Boolean = true,
)
