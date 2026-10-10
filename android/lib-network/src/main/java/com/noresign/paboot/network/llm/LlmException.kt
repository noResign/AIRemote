package com.noresign.paboot.network.llm

import java.io.IOException

/**
 * LLM 调用失败（HTTP 非 2xx / 连接失败 / 协议解析失败）。
 *
 * 供应商层对外只抛这一种异常，把底层 [com.noresign.paboot.network.sse.SseException] 等细节收在里面。
 *
 * @param httpCode 服务端状态码；连接层失败时为 `null`。
 */
class LlmException(
    val httpCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    val isUnauthorized: Boolean get() = httpCode == 401
}
