package com.noresign.paboot.network.llm

import com.noresign.paboot.network.llm.dto.ChatMessage
import com.noresign.paboot.network.llm.dto.LlmCallOptions
import com.noresign.paboot.network.llm.dto.LlmRequest
import com.noresign.paboot.network.sse.SseEvent
import com.noresign.paboot.network.sse.SseException
import com.noresign.paboot.network.sse.SseSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [OpenAiCompatLlmProvider] 的单测。
 *
 * 能这样测，正是把传输抽成 [SseSource] 接口的收益：用假的事件源喂固定帧，
 * 完全不需要真网络、也不需要 Android 环境。
 */
class OpenAiCompatLlmProviderTest {

    /** 固定的 SSE 帧序列；也可注入一个失败，用于验证异常翻译。 */
    private class FakeSseSource(
        private val payloads: List<String> = emptyList(),
        private val failure: SseException? = null,
    ) : SseSource {
        var lastRequest: Request? = null

        override fun events(request: Request): Flow<SseEvent> = flow {
            lastRequest = request
            failure?.let { throw it }
            payloads.forEach { emit(SseEvent(data = it)) }
        }
    }

    private fun provider(source: SseSource) = OpenAiCompatLlmProvider(
        config = OpenAiCompatConfig(
            baseUrl = "https://api.deepseek.com/",
            apiKey = "sk-test",
            defaultModel = "deepseek-chat",
        ),
        sse = source,
    )

    private fun request() = LlmRequest(
        messages = listOf(ChatMessage.user("hi")),
        options = LlmCallOptions(temperature = 0.3, maxOutputTokens = 128),
    )

    @Test
    fun `content and reasoning deltas are emitted and aggregated`() = runTest {
        val source = FakeSseSource(
            listOf(
                """{"model":"deepseek-reasoner","choices":[{"delta":{"reasoning_content":"想"}}]}""",
                """{"choices":[{"delta":{"content":"你"}}]}""",
                """{"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
                "[DONE]",
            ),
        )

        val events = provider(source).stream(request()).toList()

        assertEquals(
            listOf("你", "好"),
            events.filterIsInstance<LlmStreamEvent.ContentDelta>().map { it.delta },
        )
        assertEquals(
            listOf("想"),
            events.filterIsInstance<LlmStreamEvent.ReasoningDelta>().map { it.delta },
        )

        val completed = events.last()
        assertTrue("last event must be Completed", completed is LlmStreamEvent.Completed)
        val response = (completed as LlmStreamEvent.Completed).response
        assertEquals("你好", response.content)
        assertEquals("想", response.reasoningContent)
        assertEquals("stop", response.finishReason)
        assertEquals("deepseek-reasoner", response.model)
        assertEquals(5, response.totalTokens)
    }

    @Test
    fun `streamed tool calls are accumulated by index`() = runTest {
        val source = FakeSseSource(
            listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"read","arguments":"{\"pa"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a\"}"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"glob","arguments":"{}"}}]}}]}""",
                "[DONE]",
            ),
        )

        val response = provider(source).collectStream(request())
        val calls = requireNotNull(response.toolCalls) { "expected tool calls" }

        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
        assertEquals(listOf("read", "glob"), calls.map { it.function.name })
        assertEquals("{\"path\":\"a\"}", calls[0].function.arguments)
        assertEquals("{}", calls[1].function.arguments)
    }

    @Test
    fun `done sentinel and non-json frames are ignored`() = runTest {
        val source = FakeSseSource(listOf("", "not-json", ": keep-alive", """{"choices":[]}""", "[DONE]"))

        val response = provider(source).collectStream(request())

        assertEquals("", response.content)
        assertNull(response.toolCalls)
    }

    @Test
    fun `sse failure is translated into llm exception`() = runTest {
        val source = FakeSseSource(failure = SseException(httpCode = 401, message = "HTTP 401"))

        try {
            provider(source).stream(request()).toList()
            fail("expected LlmException")
        } catch (e: LlmException) {
            assertEquals(401, e.httpCode)
            assertTrue(e.isUnauthorized)
        }
    }

    @Test
    fun `request targets chat completions endpoint with auth and stream flag`() = runTest {
        val source = FakeSseSource(listOf("[DONE]"))

        provider(source).stream(request()).toList()

        val captured = requireNotNull(source.lastRequest) { "request not captured" }
        assertEquals("https://api.deepseek.com/chat/completions", captured.url.toString())
        assertEquals("Bearer sk-test", captured.header("Authorization"))

        val buffer = Buffer()
        captured.body?.writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body, body.contains("\"stream\":true"))
        assertTrue(body, body.contains("\"model\":\"deepseek-chat\""))
        assertTrue(body, body.contains("\"temperature\":0.3"))
        assertTrue(body, body.contains("\"max_tokens\":128"))
        assertTrue(body, body.contains("\"role\":\"user\""))
    }
}
