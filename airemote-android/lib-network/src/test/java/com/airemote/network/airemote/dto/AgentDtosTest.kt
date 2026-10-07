package com.airemote.network.airemote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentDto] 的 wire 契约。
 *
 * `available` 是后加的字段（daemon 用 `--version` 探测）。默认值必须是 `true`：旧 daemon
 * 不发这个字段，缺省成「不可用」会把新建会话页里所有 Agent 一起置灰，比不显示还糟。
 */
class AgentDtosTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `缺 available 时默认为可用，旧 daemon 不会把所有 Agent 置灰`() {
        val payload = """{"id":"claude","name":"Claude Code","bin":"claude"}"""

        assertTrue(json.decodeFromString(AgentDto.serializer(), payload).available)
    }

    @Test
    fun `available false 表示该运行时没装`() {
        val payload = """{"id":"codex","name":"Codex","bin":"codex","available":false}"""

        assertFalse(json.decodeFromString(AgentDto.serializer(), payload).available)
    }
}
