package com.airemote.network.sse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SseParser] 是纯逻辑（不碰 IO），所以可以直接单测——这也是把它从传输层拆出来的原因。
 */
class SseParserTest {

    private fun feedAll(vararg lines: String): List<SseEvent> {
        val parser = SseParser()
        val events = mutableListOf<SseEvent>()
        for (line in lines) parser.feed(line)?.let { events += it }
        return events
    }

    @Test
    fun `data frame is dispatched on blank line`() {
        val events = feedAll("data: {\"a\":1}", "")
        assertEquals(1, events.size)
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `leading space after colon is stripped`() {
        val events = feedAll("data:no-space", "")
        assertEquals("no-space", events[0].data)
    }

    @Test
    fun `multi-line data is joined with newline`() {
        val events = feedAll("data: line1", "data: line2", "")
        assertEquals(1, events.size)
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun `comment line is ignored`() {
        // daemon 每次连接先发 ": connected"，之后是 keepalive 注释；
        // OpenAI 兼容接口也会发 ": keep-alive"
        val events = feedAll(": connected", "", ": keepalive", "", "data: x", "")
        assertEquals(1, events.size)
        assertEquals("x", events[0].data)
    }

    @Test
    fun `id event and retry fields are parsed`() {
        val events = feedAll("id: 7", "event: foo", "retry: 3000", "data: payload", "")
        assertEquals(1, events.size)
        assertEquals("7", events[0].id)
        assertEquals("foo", events[0].type)
        assertEquals(3000L, events[0].retryMs ?: -1L)
        assertEquals("payload", events[0].data)
    }

    @Test
    fun `flush emits pending event when stream ends without blank line`() {
        val parser = SseParser()
        assertNull(parser.feed("data: tail"))
        val event = parser.flush()
        assertEquals("tail", event?.data)
    }

    @Test
    fun `blank line with nothing pending emits nothing`() {
        assertNull(SseParser().feed(""))
    }
}
