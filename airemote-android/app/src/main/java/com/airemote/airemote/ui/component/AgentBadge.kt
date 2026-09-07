package com.airemote.airemote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airemote.airemote.ui.identity.RuntimeIdentities

/**
 * runtime 身份徽章（§5.2）：图标 + 展示名，主题色由 [RuntimeIdentities] 决定。
 * 出现位置：会话列表卡片、聊天页顶栏、新建会话 agent 选择器、设置页默认 agent。
 */
@Composable
fun AgentBadge(
    runtimeId: String,
    modifier: Modifier = Modifier,
) {
    val identity = RuntimeIdentities.of(runtimeId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(identity.color.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = identity.icon,
            contentDescription = null,
            tint = identity.color,
            modifier = Modifier.size(13.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = identity.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = identity.color,
        )
    }
}
