package com.noresign.paboot.network.llm.dto

import kotlinx.serialization.json.JsonElement

/**
 * 采样 / 输出控制选项。`extra` 用于透传任意厂商私有字段（直接合并进请求体）。
 */
data class LlmCallOptions(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    /** `enabled` / `disabled` / `auto`（DeepSeek thinking）。 */
    val thinkingType: String? = null,
    /** `text` / `json_object`。 */
    val responseFormat: String? = null,
    /** 额外透传字段。 */
    val extra: Map<String, JsonElement> = emptyMap(),
)
