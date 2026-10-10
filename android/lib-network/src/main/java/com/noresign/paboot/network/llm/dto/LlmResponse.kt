package com.noresign.paboot.network.llm.dto

/**
 * 聚合后的 LLM 响应（流式 / 非流式统一形状）。
 *
 * 字段用 `var` 是为了流式聚合时能原地填充。
 */
data class LlmResponse(
    var content: String = "",
    var reasoningContent: String? = null,
    var finishReason: String? = null,
    var model: String? = null,
    var promptTokens: Int? = null,
    var completionTokens: Int? = null,
    var totalTokens: Int? = null,
    var toolCalls: List<ToolCall>? = null,
)
