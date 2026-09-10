package com.airemote.network.sse

import java.io.IOException

/**
 * SSE 传输层异常：HTTP 非 2xx、响应体为空、或连接/读取失败。
 *
 * 通用层统一用异常表达失败（而不是业务事件），由上层决定怎么展示。
 *
 * @param httpCode 服务端返回的状态码；连接层失败（无响应）时为 `null`。
 */
class SseException(
    val httpCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    /** 是否为鉴权失败。 */
    val isUnauthorized: Boolean get() = httpCode == 401
}
