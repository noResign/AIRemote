package com.airemote.network.llm

/** OpenAI 兼容服务的接入配置。 */
data class OpenAiCompatConfig(
    /** 形如 `https://api.deepseek.com`（末尾斜杠可有可无）；实际请求 `{baseUrl}/chat/completions`。 */
    val baseUrl: String,
    val apiKey: String,
    /** 请求未指定 `model` 时的默认模型。 */
    val defaultModel: String? = null,
    /** 追加请求头（可选，比如某些兼容网关需要的额外字段）。 */
    val extraHeaders: Map<String, String> = emptyMap(),
)
