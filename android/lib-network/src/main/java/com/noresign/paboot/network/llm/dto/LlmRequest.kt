package com.noresign.paboot.network.llm.dto

import kotlinx.serialization.json.JsonArray

/** 一次 LLM 调用的输入（与厂商无关）。 */
data class LlmRequest(
    /** 模型 id；为空时用 provider 配置里的默认模型。 */
    val model: String? = null,
    val messages: List<ChatMessage>,
    val options: LlmCallOptions? = null,
    /** 工具定义（OpenAI `tools` 数组，原样透传）。 */
    val tools: JsonArray? = null,
    /** `auto` / `none` / 具体函数名。 */
    val toolChoice: String? = null,
)
