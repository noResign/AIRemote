package com.airemote.airemote.model.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 顶栏那条上下文占用文案：有分母给百分比，没分母只给占用（不编分母）。 */
class ContextUsageTest {

    @Test
    fun `shows occupancy, window and percentage`() {
        val usage = ContextUsage(tokens = 45_200, window = 168_000)

        assertEquals(27, usage.percent)
        assertEquals("45.2k / 168k · 27%", usage.display)
    }

    @Test
    fun `clamps the percentage and never divides by zero`() {
        // 上报值理论上不会超过窗口，但真超了就显示 100%，不能出现 118% 这种东西。
        assertEquals(100, ContextUsage(tokens = 200_000, window = 168_000).percent)

        // 非正数窗口按「未知」处理，不产生 Infinity/NaN 百分比。
        assertNull(ContextUsage(tokens = 1_000, window = 0).percent)
        assertEquals("1k tokens", ContextUsage(tokens = 1_000, window = 0).display)
    }

    @Test
    fun `falls back to occupancy alone when the runtime reports no window`() {
        val usage = ContextUsage(tokens = 5_300, window = null)

        assertNull(usage.percent)
        assertEquals("5.3k tokens", usage.display)
    }
}
