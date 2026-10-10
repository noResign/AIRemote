package com.noresign.paboot.network.llm.dto

import kotlinx.serialization.Serializable

/** OpenAI 风格的函数调用（请求里回传 / 非流式响应里返回）。 */
@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: Function = Function(),
) {
    @Serializable
    data class Function(
        val name: String = "",
        val arguments: String = "",
    )
}
