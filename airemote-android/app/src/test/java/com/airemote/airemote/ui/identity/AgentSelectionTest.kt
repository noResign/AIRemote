package com.airemote.airemote.ui.identity

import com.airemote.network.airemote.dto.AgentDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 新建会话的默认 Agent：跳过没装的。
 *
 * 列表顺序来自 daemon 注册顺序，和「装没装」无关；取 `first()` 会让用户一进新建页
 * 就选中一个发不出去的 Agent。
 */
class AgentSelectionTest {

    @Test
    fun `默认选中第一个可用的 Agent，而不是列表里的第一个`() {
        val agents = listOf(
            AgentDto(id = "codex", name = "Codex", available = false),
            AgentDto(id = "claude", name = "Claude Code", available = true),
        )
        assertEquals("claude", defaultAgentId(agents))
    }

    @Test
    fun `都可用时取第一个，保持注册顺序`() {
        val agents = listOf(
            AgentDto(id = "claude", available = true),
            AgentDto(id = "codex", available = true),
        )
        assertEquals("claude", defaultAgentId(agents))
    }

    @Test
    fun `一个都没装时返回 null`() {
        assertNull(
            defaultAgentId(
                listOf(
                    AgentDto(id = "claude", available = false),
                    AgentDto(id = "codex", available = false),
                ),
            ),
        )
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(defaultAgentId(emptyList()))
    }
}
