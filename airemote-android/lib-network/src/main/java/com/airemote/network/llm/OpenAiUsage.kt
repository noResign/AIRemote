package com.airemote.network.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** OpenAI 兼容响应里的 `usage`（流式 chunk 与非流式响应共用）。 */
@Serializable
internal data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)
