package com.airemote.airemote.model.chat

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
