package com.noresign.paboot.network.sse

/**
 * 有状态的 SSE 行解析器（**纯逻辑、不碰 IO**，因此可单测）。
 *
 * 按 SSE 规范累积字段，遇到空行输出一条 [SseEvent]：
 * ```
 * event: foo\n
 * data: {"a":1}\n
 * \n              → SseEvent(type="foo", data="{\"a\":1}")
 * ```
 *
 * 支持 `id` / `event` / `data` / `retry`；`:` 开头视为注释（keepalive）忽略；
 * 无冒号的行按「字段名 + 空值」处理；`data` 多行用 `\n` 连接。
 */
class SseParser {

    private var id: String? = null
    private var type: String? = null
    private var retryMs: Long? = null
    private val data = StringBuilder()

    /** 喂入一行（不含行尾换行符）；若该行结束了一条事件则返回它，否则返回 `null`。 */
    fun feed(line: String): SseEvent? = when {
        line.isEmpty() -> drain()
        line.startsWith(":") -> null // 注释 / keepalive
        else -> {
            applyField(line)
            null
        }
    }

    /** 流结束时调用：冲掉服务端未以空行收尾的残留事件。 */
    fun flush(): SseEvent? = drain()

    private fun applyField(line: String) {
        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        // 规范：冒号后紧跟的一个空格不算值的一部分
        val value = if (colon >= 0) line.substring(colon + 1).removePrefix(" ") else ""
        when (field) {
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            "event" -> type = value
            "id" -> id = value
            "retry" -> retryMs = value.toLongOrNull()
            else -> Unit // 未知字段忽略
        }
    }

    private fun drain(): SseEvent? {
        if (data.isEmpty() && id == null && type == null && retryMs == null) return null
        val event = SseEvent(id = id, type = type, data = data.toString(), retryMs = retryMs)
        id = null
        type = null
        retryMs = null
        data.setLength(0)
        return event
    }
}
