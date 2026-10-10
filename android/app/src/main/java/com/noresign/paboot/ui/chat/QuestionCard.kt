package com.noresign.paboot.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noresign.paboot.model.chat.ContentBlock
import com.noresign.paboot.network.daemon.dto.QuestionDto

@Composable
internal fun QuestionCard(
    block: ContentBlock.Question,
    streaming: Boolean,
    onAnswer: (String, String) -> Unit,
) {
    // 每个问题的选中状态：问题下标 → 已选 label 集合（流式中也能预选，本地状态）
    val selections = remember(block.toolUseId) { mutableStateMapOf<Int, Set<String>>() }

    fun selected(i: Int): Set<String> = selections[i] ?: emptySet()

    val allAnswered = block.questions.indices.all { i -> selected(i).isNotEmpty() }

    // 全部选完 + 流结束 + 未发送 → 自动把答案一次性发出去
    LaunchedEffect(streaming, allAnswered, block.answered) {
        if (!streaming && allAnswered && !block.answered) {
            onAnswer(block.toolUseId, buildAnswerText(block.questions, selections))
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            block.questions.forEachIndexed { i, q ->
                QuestionItem(
                    question = q,
                    selected = selected(i),
                    enabled = !block.answered,
                    onToggle = { label ->
                        val cur = selected(i)
                        selections[i] = if (q.multiSelect) {
                            if (label in cur) cur - label else cur + label
                        } else {
                            setOf(label)
                        }
                    },
                )
            }
            if (block.answered) {
                Text(
                    text = "已发送答案",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (block.questions.size > 1 && !allAnswered) {
                Text(
                    text = "还有问题未选择",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun QuestionItem(
    question: QuestionDto,
    selected: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val header = question.header
        if (!header.isNullOrBlank()) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = question.question,
            style = MaterialTheme.typography.titleSmall,
        )
        question.options.forEach { opt ->
            OptionRow(
                label = opt.label,
                description = opt.description,
                checked = opt.label in selected,
                multiSelect = question.multiSelect,
                enabled = enabled,
                onClick = { onToggle(opt.label) },
            )
        }
    }
}

@Composable
internal fun OptionRow(
    label: String,
    description: String?,
    checked: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        if (multiSelect) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        } else {
            RadioButton(selected = checked, onClick = null, enabled = enabled)
        }
        OptionLabel(label, description)
    }
}

@Composable
internal fun OptionLabel(label: String, description: String?) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun buildAnswerText(questions: List<QuestionDto>, selections: Map<Int, Set<String>>): String =
    questions.mapIndexed { i, q ->
        "关于「${q.question}」，我的选择是：${(selections[i] ?: emptySet()).joinToString("、")}"
    }.joinToString("\n")
