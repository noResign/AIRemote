package com.airemote.airemote.model.chat

import kotlinx.serialization.json.JsonElement

/** 聊天页里一条可渲染的消息（用户 / 助手）。 */
sealed interface ChatUiMessage {
    data class User(val text: String) : ChatUiMessage

    data class Assistant(
        val thinking: String = "",
        val text: String = "",
        val tools: List<ToolCard> = emptyList(),
        val usage: UsageInfo? = null,
        val error: String? = null,
        val done: Boolean = false,
    ) : ChatUiMessage
}

data class ToolCard(
    val id: String,
    val name: String,
    val input: JsonElement? = null,
    val result: String? = null,
    val isError: Boolean = false,
    val running: Boolean = true,
)

data class UsageInfo(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
)
