package com.airemote.airemote.util

import java.util.concurrent.TimeUnit

/** 把 epoch 毫秒格式化成相对时间（"刚刚 / n 分钟前 / n 小时前 / n 天前"）。 */
fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val diff = System.currentTimeMillis() - epochMs
    if (diff < 0) return "刚刚"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    if (minutes < 1) return "刚刚"
    if (minutes < 60) return "${minutes} 分钟前"
    val hours = TimeUnit.MINUTES.toHours(minutes)
    if (hours < 24) return "${hours} 小时前"
    val days = TimeUnit.HOURS.toDays(hours)
    return "${days} 天前"
}

/**
 * token 数的缩写形式：`999` / `45.2k` / `168k` / `1.2M`。
 *
 * 十万以内保留一位小数（token 计数在这个量级上，小数位有信息量），再往上取整。
 */
fun formatTokens(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 100_000 -> scaled(n, 1_000, "k")
    n < 1_000_000 -> "${n / 1_000}k"
    else -> scaled(n, 1_000_000, "M")
}

/**
 * 一位小数，整数尾数不显示（`1.0k` → `1k`）。
 *
 * 手算而不是 `String.format`：结果不该随系统 locale 变（有些 locale 的小数点是逗号），
 * 而 Kotlin 那个带 Locale 的 `String.format` 重载是被隐藏的（deprecated-hidden）。
 */
private fun scaled(n: Long, unit: Long, suffix: String): String {
    val tenths = Math.round(n.toDouble() * 10 / unit)
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0L) "$whole$suffix" else "$whole.$frac$suffix"
}
