package com.airemote.airemote.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.airemote.airemote.model.chat.TodoItem

@Composable
internal fun TodoListPanel(todos: List<TodoItem>, running: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val completed = todos.count { it.status == "completed" }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "任务列表",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$completed/${todos.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    todos.forEach { item -> TodoRow(item, running) }
                }
            }
        }
    }
}

@Composable
internal fun TodoRow(item: TodoItem, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            "completed" -> Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            "in_progress" -> if (running) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text("●", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            else -> Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.content,
            style = when (item.status) {
                "completed" -> MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                )
                "in_progress" -> MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                else -> MaterialTheme.typography.bodyMedium
            },
        )
    }
}
