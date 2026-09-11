package com.airemote.network.airemote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EventDtos] 的 wire 契约测试。
 *
 * 归一化事件以 `type` 字段做多态判别，这条约定是跨端最容易悄悄写错的地方（daemon 发 `text_delta`，
 * 客户端少一个 `@SerialName` 就整帧解析失败），所以这里逐个子类钉死。
 */
class EventDtosTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `sse frame decodes run metadata and event together`() {
        val payload = """{"runId":"r1","seq":7,"event":{"type":"text_delta","delta":"你好"}}"""

        val frame = json.decodeFromString(SseFrame.serializer(), payload)

        assertEquals("r1", frame.runId)
        assertEquals(7L, frame.seq)
        assertEquals("你好", (frame.event as NormalizedEvent.TextDelta).delta)
    }

    @Test
    fun `every normalized event variant decodes from its type tag`() {
        val cases = listOf(
            """{"type":"status","label":"running"}""" to NormalizedEvent.Status::class,
            """{"type":"text_delta","delta":"a"}""" to NormalizedEvent.TextDelta::class,
            """{"type":"thinking_delta","delta":"a"}""" to NormalizedEvent.ThinkingDelta::class,
            """{"type":"thinking_start"}""" to NormalizedEvent.ThinkingStart::class,
            """{"type":"tool_use","id":"t1","name":"Bash"}""" to NormalizedEvent.ToolUse::class,
            """{"type":"tool_result","content":"ok"}""" to NormalizedEvent.ToolResult::class,
            """{"type":"usage","costUsd":0.01}""" to NormalizedEvent.Usage::class,
            """{"type":"turn_end","stopReason":"end_turn"}""" to NormalizedEvent.TurnEnd::class,
            """{"type":"error","message":"boom"}""" to NormalizedEvent.Error::class,
            """{"type":"permission_request","permissionId":"p1","toolName":"Bash"}"""
                to NormalizedEvent.PermissionRequest::class,
            """{"type":"question","toolUseId":"q1","questions":[{"question":"What?","header":"H","multiSelect":false,"options":[{"label":"A","description":"d"}]}]}"""
                to NormalizedEvent.Question::class,
        )

        for ((payload, expected) in cases) {
            val event = json.decodeFromString(NormalizedEvent.serializer(), payload)
            assertEquals(payload, expected, event::class)
        }
    }

    @Test
    fun `turn end carries the stop reason`() {
        val event = json.decodeFromString(
            NormalizedEvent.serializer(),
            """{"type":"turn_end","stopReason":"end_turn"}""",
        )

        assertEquals("end_turn", (event as NormalizedEvent.TurnEnd).stopReason)
    }

    @Test
    fun `unknown fields on a known event are tolerated`() {
        val event = json.decodeFromString(
            NormalizedEvent.serializer(),
            """{"type":"tool_result","content":"ok","somethingNew":123}""",
        )

        assertEquals("ok", (event as NormalizedEvent.ToolResult).content)
    }

    @Test
    fun `chat request omits unset optional fields`() {
        val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(prompt = "hi"))

        assertTrue(body, body.contains("\"prompt\":\"hi\""))
        // 默认值不参与编码：daemon 侧靠字段缺省来走默认（不传 sessionId = 开新会话）
        assertFalse(body, body.contains("sessionId"))
        assertFalse(body, body.contains("runtime"))
    }
}
