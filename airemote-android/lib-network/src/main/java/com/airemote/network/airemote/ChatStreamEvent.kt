package com.airemote.network.airemote

import com.airemote.network.airemote.dto.SseFrame

/**
 * airemote 聊天流的**业务层**事件（在通用 [com.airemote.network.sse.SseSource] 之上）。
 *
 * 换成别的 AI 接入时，照着写一份类似的「域适配层」即可，通用 SSE 层不用动。
 */
sealed interface ChatStreamEvent {
    /** 一帧归一化事件（`{runId, seq, event}`）。 */
    data class Frame(val frame: SseFrame) : ChatStreamEvent

    /**
     * 连接/协议失败（已翻译成人可读文案）。
     *
     * [httpCode] 为 HTTP 状态码，连接层失败（超时、断网）时为 null。调用方据此区分
     * 「可重试」（网络错误 / 5xx）与「重试无意义」（400 / 401 / 403 / 404）。
     *
     * [apiCode] 是 daemon 错误正文里的 `code`。业务层用它做精确文案映射；[message] 是
     * daemon 的英文原文，只作兜底。
     */
    data class Failed(
        val message: String,
        val httpCode: Int? = null,
        val apiCode: String? = null,
    ) : ChatStreamEvent

    /** 服务端正常结束流。 */
    data object Closed : ChatStreamEvent
}