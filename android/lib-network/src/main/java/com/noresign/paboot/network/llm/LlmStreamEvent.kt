package com.noresign.paboot.network.llm

import com.noresign.paboot.network.llm.dto.LlmResponse

/** 流式响应事件（Kotlin 用 Flow 承载，替代回调式 handler）。 */
sealed interface LlmStreamEvent {

    /** 正文增量。 */
    data class ContentDelta(val delta: String) : LlmStreamEvent

    /** 思维链增量（DeepSeek `reasoning_content` 等）。 */
    data class ReasoningDelta(val delta: String) : LlmStreamEvent

    /** 流结束：携带聚合结果（content / reasoning / usage / toolCalls / finishReason）。 */
    data class Completed(val response: LlmResponse) : LlmStreamEvent
}
