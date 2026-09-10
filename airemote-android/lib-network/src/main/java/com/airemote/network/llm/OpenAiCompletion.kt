package com.airemote.network.llm

import com.airemote.network.llm.dto.ToolCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI 兼容**非流式**响应（`stream = false`）。 */
@Serializable
internal data class OpenAiCompletion(
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: OpenAiUsage? = null,
) {
    @Serializable
    internal data class Choice(
        val index: Int = 0,
        val message: Message = Message(),
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    internal data class Message(
        val role: String? = null,
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    )
}
