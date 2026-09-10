package com.airemote.network.sse

/**
 * 一条原始 SSE 事件（对应一组 `id:` / `event:` / `data:` / `retry:` 字段，以空行收尾）。
 *
 * **通用传输层模型**，不含任何业务语义——可被不同后端（daemon / 云端大模型）复用。
 */
data class SseEvent(
    /** `id:` 字段（可作为续传游标）。 */
    val id: String? = null,
    /** `event:` 字段；缺省表示 SSE 默认的 `message` 类型。 */
    val type: String? = null,
    /** `data:` 累积内容；多行按规范用 `\n` 连接。 */
    val data: String = "",
    /** `retry:` 字段（重连建议间隔，毫秒）。 */
    val retryMs: Long? = null,
)
