package com.airemote.airemote.model.chat

import kotlinx.serialization.json.JsonElement

/** 助手消息里的一段有序内容块，按 Claude 实际返回的顺序排列。 */
sealed interface ContentBlock {
    data class Thinking(val text: String) : ContentBlock

    data class Text(val text: String) : ContentBlock

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement? = null,
        val result: String? = null,
        val isError: Boolean = false,
        val running: Boolean = true,
    ) : ContentBlock
}
