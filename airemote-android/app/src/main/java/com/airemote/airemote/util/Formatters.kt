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
