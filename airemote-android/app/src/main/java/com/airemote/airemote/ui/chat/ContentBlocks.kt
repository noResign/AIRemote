package com.airemote.airemote.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.airemote.airemote.model.chat.ContentBlock
import com.airemote.airemote.model.chat.UsageInfo
import com.airemote.airemote.ui.theme.CodeBody
import com.airemote.airemote.ui.theme.LocalSemanticColors

@Composable
internal fun TextBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun ThinkingBlock(thinking: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val thinkingColor = LocalSemanticColors.current.thinking
            Text(
                text = if (expanded) "▾ 思考中（点击收起）" else "▸ 思考中（点击展开）",
                style = MaterialTheme.typography.labelSmall,
                color = thinkingColor,
            )
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = thinking,
                    style = CodeBody,
                    color = thinkingColor,
                )
            }
        }
    }
}

@Composable
internal fun ToolCardView(card: ContentBlock.ToolUse) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    card.running -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    card.isError -> Icon(
                        Icons.Rounded.Close,
                        contentDescription = "失败",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    else -> Icon(
                        Icons.Rounded.Check,
                        contentDescription = "完成",
                        tint = LocalSemanticColors.current.success,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                if (card.input != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CodeBlock(card.input.toString())
                }
                if (!card.result.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CodeBlock(card.result)
                }
            }
        }
    }
}

@Composable
internal fun CodeBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
            Text(
                text = text,
                style = CodeBody,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
internal fun UsageLine(usage: UsageInfo) {
    val parts = mutableListOf<String>()
    usage.inputTokens?.let { parts.add("↑$it") }
    usage.outputTokens?.let { parts.add("↓$it") }
    val cost = usage.costUsd?.let { "$" + "%.2f".format(it) }
    val tokens = if (parts.isNotEmpty()) parts.joinToString(" ") + " tokens" else ""
    val line = listOfNotNull(tokens, cost).filter { it.isNotBlank() }.joinToString(" · ")
    if (line.isNotBlank()) {
        Text(
            text = line,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
