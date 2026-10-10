package com.noresign.paboot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 断线重连的判定逻辑（纯函数，无需 Android 环境）。 */
class ReconnectPolicyTest {

    // ---- 退避 ----

    @Test
    fun `backoff 按 1s 起步逐档翻倍`() {
        // jitter = 0.5 → 系数 1.0，取到表里的原值
        assertEquals(1_000L, ReconnectPolicy.backoffMs(0, 0.5))
        assertEquals(2_000L, ReconnectPolicy.backoffMs(1, 0.5))
        assertEquals(4_000L, ReconnectPolicy.backoffMs(2, 0.5))
        assertEquals(8_000L, ReconnectPolicy.backoffMs(3, 0.5))
        assertEquals(15_000L, ReconnectPolicy.backoffMs(4, 0.5))
    }

    @Test
    fun `backoff 在 30s 封顶，不会随轮次无限增长`() {
        assertEquals(30_000L, ReconnectPolicy.backoffMs(5, 0.5))
        assertEquals(30_000L, ReconnectPolicy.backoffMs(50, 0.5))
    }

    @Test
    fun `backoff 抖动落在 0_5 到 1_5 倍之间`() {
        val base = 30_000L
        assertEquals(base / 2, ReconnectPolicy.backoffMs(5, 0.0))
        assertEquals(base * 3 / 2, ReconnectPolicy.backoffMs(5, 1.0))
    }

    // ---- 可重试性 ----

    @Test
    fun `鉴权失败与请求错误不重试`() {
        listOf(400, 401, 403, 404).forEach { code ->
            assertTrue("HTTP $code 应当不可重试", ReconnectPolicy.isNonRetryable(code))
        }
    }

    @Test
    fun `服务端错误与连接层失败都可重试`() {
        listOf(408, 500, 502, 503, null).forEach { code ->
            assertFalse("HTTP $code 应当可重试", ReconnectPolicy.isNonRetryable(code))
        }
    }

    // ---- 判定 ----

    @Test
    fun `收到终局事件就结束，不看其他条件`() {
        val decision = ReconnectPolicy.decide(
            terminal = true,
            httpCode = 401,
            runActive = null,
            unreachableMs = Long.MAX_VALUE,
            attempt = 9,
            jitter = 0.5,
        )
        assertEquals(ReconnectPolicy.Decision.Finished, decision)
    }

    @Test
    fun `不可重试的状态码直接放弃`() {
        val decision = ReconnectPolicy.decide(
            terminal = false,
            httpCode = 404,
            runActive = true,
            unreachableMs = 0,
            attempt = 0,
            jitter = 0.5,
        )
        assertEquals(ReconnectPolicy.Decision.GiveUp, decision)
    }

    /** daemon 重启 / run 自然结束 / 被看门狗取消：run 不在内存里了就收口，绝不能陷入重连循环。 */
    @Test
    fun `run 已从 daemon 消失则收口`() {
        val decision = ReconnectPolicy.decide(
            terminal = false,
            httpCode = null,
            runActive = false,
            unreachableMs = 0,
            attempt = 0,
            jitter = 0.5,
        )
        assertEquals(ReconnectPolicy.Decision.Settled, decision)
    }

    /** 中间设备掐断长连接时服务端会优雅结束流，此时 run 其实还活着，必须继续重连。 */
    @Test
    fun `run 仍在运行就退避重连`() {
        val decision = ReconnectPolicy.decide(
            terminal = false,
            httpCode = null,
            runActive = true,
            unreachableMs = 0,
            attempt = 1,
            jitter = 0.5,
        )
        assertEquals(ReconnectPolicy.Decision.Retry(2_000L), decision)
    }

    @Test
    fun `探测不到 daemon 时先重试`() {
        val decision = ReconnectPolicy.decide(
            terminal = false,
            httpCode = null,
            runActive = null,
            unreachableMs = 0,
            attempt = 0,
            jitter = 0.5,
        )
        assertTrue(decision is ReconnectPolicy.Decision.Retry)
    }

    /** 笔记本休眠这类"daemon 整体不可达"的场景：给一个预算，避免在后台无限空转。 */
    @Test
    fun `探测不到 daemon 超过预算就放弃`() {
        val decision = ReconnectPolicy.decide(
            terminal = false,
            httpCode = null,
            runActive = null,
            unreachableMs = ReconnectPolicy.UNREACHABLE_BUDGET_MS,
            attempt = 3,
            jitter = 0.5,
        )
        assertEquals(ReconnectPolicy.Decision.GiveUp, decision)
    }

    /** 预算只约束"探测不到"；run 确认还活着时不受它限制。 */
    @Test
    fun `run 仍活着时不受不可达预算限制`() {
        val decision = ReconnectPolicy.decide(
            terminal = false,
            httpCode = null,
            runActive = true,
            unreachableMs = ReconnectPolicy.UNREACHABLE_BUDGET_MS * 10,
            attempt = 9,
            jitter = 0.5,
        )
        assertTrue(decision is ReconnectPolicy.Decision.Retry)
    }
}
