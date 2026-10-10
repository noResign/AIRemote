package com.noresign.paboot.model.chat

import com.noresign.paboot.util.formatTokens
import kotlin.math.roundToInt

/**
 * 当前上下文窗口的占用（聊天页顶栏展示）。
 *
 * 与消息末尾的 [UsageInfo] 是两回事：那个是「本 run 累计花了多少」，这个回答「现在窗口
 * 里装了多少」——后者会随上下文压缩**回落**，所以不复用同一个模型。
 *
 * [window] 为 null 表示运行时没给出窗口大小（Claude 就是如此）。此时只显示占用、不显示
 * 百分比：猜一个分母会给出**错误的百分比**，比没有分母更糟。
 */
data class ContextUsage(
    val tokens: Long,
    val window: Long?,
) {
    /** 0..100；窗口未知或非正数时为 null。 */
    val percent: Int?
        get() = window?.takeIf { it > 0 }?.let { ((tokens * 100.0) / it).roundToInt().coerceIn(0, 100) }

    /** 有分母 `45.2k / 168k · 27%`，没有分母 `45.2k tokens`。 */
    val display: String
        get() = window?.takeIf { it > 0 }?.let { w ->
            "${formatTokens(tokens)} / ${formatTokens(w)} · $percent%"
        } ?: "${formatTokens(tokens)} tokens"
}
