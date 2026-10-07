package com.airemote.airemote.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** [formatTokens] 的缩写规则：<1k 原样、<100k 一位小数、<1M 取整、其余一位小数带 M。 */
class FormattersTest {

    @Test
    fun `keeps small counts as-is`() {
        assertEquals("0", formatTokens(0))
        assertEquals("999", formatTokens(999))
    }

    @Test
    fun `uses one decimal below 100k`() {
        assertEquals("1k", formatTokens(1_000))
        assertEquals("45.2k", formatTokens(45_200))
        assertEquals("99.9k", formatTokens(99_900))
    }

    @Test
    fun `drops the decimal at and above 100k`() {
        assertEquals("100k", formatTokens(100_000))
        assertEquals("168k", formatTokens(168_000))
        assertEquals("168k", formatTokens(168_500))
    }

    @Test
    fun `switches to M at a million`() {
        assertEquals("1M", formatTokens(1_000_000))
        assertEquals("1.2M", formatTokens(1_200_000))
    }

    @Test
    fun `decimal point does not follow the system locale`() {
        // 有些 locale 的小数点是逗号；这个数字是给人看的，必须固定成点。
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("45.2k", formatTokens(45_200))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
