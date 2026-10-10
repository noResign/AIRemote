package com.noresign.paboot.network.llm.dto

/** 一条对话消息（OpenAI 风格 `role` / `content`）。 */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    /** 思维链内容（DeepSeek `reasoning_content`；assistant 消息回传时用）。 */
    val reasoningContent: String? = null,
    /** `role = tool` 时对应的调用 id。 */
    val toolCallId: String? = null,
    /** assistant 请求调用工具时携带。 */
    val toolCalls: List<ToolCall>? = null,
) {
    companion object {
        fun system(content: String) = ChatMessage(role = "system", content = content)
        fun user(content: String) = ChatMessage(role = "user", content = content)
        fun assistant(content: String) = ChatMessage(role = "assistant", content = content)
        fun tool(toolCallId: String, content: String) =
            ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
    }
}
