package com.airemote.airemote.ui.identity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.airemote.airemote.ui.theme.DesignColors

/**
 * 一个 runtime（agent）的 UI 身份：图标 + 主题色 + 展示名（docs/ui/design-principles.md §5.2）。
 * 新增 agent 只需在 [RuntimeIdentities] 的 table 里加一行，不改布局。
 */
data class RuntimeIdentity(
    val id: String,
    val displayName: String,
    val icon: ImageVector,
    val color: Color,
)

object RuntimeIdentities {

    private val table = mapOf(
        "claude" to RuntimeIdentity(
            id = "claude",
            displayName = "Claude Code",
            icon = Icons.Rounded.SmartToy,
            color = DesignColors.ClaudeOrange,
        ),
        // 未来：codex / opencode / deepseek-harness 各加一行
    )

    /** 未知 id 兜底：build 图标 + 中性色 + 直接展示后端 name（§5.2）。 */
    private val neutralFallback = Color(0xFF8A949E)

    fun of(id: String): RuntimeIdentity = table[id] ?: RuntimeIdentity(
        id = id,
        displayName = id.ifBlank { "agent" },
        icon = Icons.Rounded.Build,
        color = neutralFallback,
    )
}
