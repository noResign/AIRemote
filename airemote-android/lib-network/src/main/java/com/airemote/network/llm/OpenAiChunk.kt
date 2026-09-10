package com.airemote.network.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI 兼容**流式** chunk（`stream = true` 时每个 `data:` 帧；`[DONE]` 不是 JSON，单独处理）。 */
@Serializable
internal data class OpenAiChunk(
    val model: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: OpenAiUsage? = null,
) {
    @Serializable
    internal data class Choice(
        val index: Int = 0,
        val delta: Delta = Delta(),
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    internal data class Delta(
        val role: String? = null,
        val content: String? = null,
        /** DeepSeek 思维链增量。 */
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        /** 流式工具调用：按 `index` 分片累积。 */
        @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
    )

    @Serializable
    internal data class ToolCallDelta(
        val index: Int = 0,
        val id: String? = null,
        val type: String? = null,
        val function: FunctionDelta? = null,
    )

    @Serializable
    internal data class FunctionDelta(
        val name: String? = null,
        val arguments: String? = null,
    )
}
