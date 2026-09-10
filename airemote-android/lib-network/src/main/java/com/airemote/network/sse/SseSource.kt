package com.airemote.network.sse

import kotlinx.coroutines.flow.Flow
import okhttp3.Request

/**
 * **通用 SSE 事件源**：给一个 [Request]，返回一条 [SseEvent] 冰冷的 `Flow`。
 *
 * 抽象成接口的目的：
 *  - 上层业务只依赖抽象，便于替换实现（换 client / 加拦截 / 测试用 fake）；
 *  - 不掺业务语义，可单独抽成底层库给其他后端接入复用。
 *
 * 约定：
 *  - 正常读到流末尾（服务端 `res.end()`）→ `Flow` **正常结束**；
 *  - HTTP 非 2xx / 连接或读取失败 → 抛 [SseException]；
 *  - 取消 collector → 中断底层连接（由实现负责）。
 */
interface SseSource {
    fun events(request: Request): Flow<SseEvent>
}
