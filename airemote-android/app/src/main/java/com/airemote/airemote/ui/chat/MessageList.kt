package com.airemote.airemote.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airemote.airemote.model.chat.ChatUiMessage
import com.airemote.airemote.model.chat.ContentBlock

@Composable
internal fun MessageList(
    messages: List<ChatUiMessage>,
    streaming: Boolean,
    onAnswer: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 流式更新时只在底部跟随，否则会把用户的上滑动作抢回到底部
    var autoScroll by remember { mutableStateOf(true) }

    // reverseLayout 下 index 0 是底部（最新消息）；scrollOffset == 0 表示没往上滚。
    // 相比「最后一个 item 是否可见」，这个判定对超过一屏的长消息也准确。
    val atBottom by remember {
        derivedStateOf {
            listState.layoutInfo.totalItemsCount == 0 ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
        }
    }

    // 新消息（user + assistant 一起追加）→ 跳到底并恢复跟随
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(0)
            autoScroll = true
        }
    }

    // 跟随状态跟随「是否在底部」：上滑即暂停，回到底部即恢复
    LaunchedEffect(atBottom) {
        autoScroll = atBottom
    }

    val lastContentLen = (messages.lastOrNull() as? ChatUiMessage.Assistant)?.blocks?.sumOf { block ->
        when (block) {
            is ContentBlock.Text -> block.text.length
            is ContentBlock.Thinking -> block.text.length
            is ContentBlock.ToolUse -> (block.result?.length ?: 0) + 1
            is ContentBlock.Question -> 1
        }
    } ?: 0
    LaunchedEffect(lastContentLen) {
        if (autoScroll && messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            count = messages.size,
            key = { index -> messages.size - 1 - index },
        ) { index ->
            when (val message = messages[messages.size - 1 - index]) {
                is ChatUiMessage.User -> UserBubble(message.text)
                is ChatUiMessage.Assistant -> AssistantBlock(message, streaming, onAnswer)
            }
        }
    }
}

@Composable
internal fun UserBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
internal fun AssistantBlock(
    message: ChatUiMessage.Assistant,
    streaming: Boolean,
    onAnswer: (String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Thinking -> ThinkingBlock(block.text)
                is ContentBlock.Text -> TextBlock(block.text)
                is ContentBlock.ToolUse -> ToolCardView(block)
                is ContentBlock.Question -> QuestionCard(block, streaming, onAnswer)
            }
        }
        if (message.error != null) {
            Text(
                text = message.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (message.done && message.blocks.isEmpty() && message.error == null) {
            Text(
                text = "（无回复）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message.usage?.let { UsageLine(it) }
    }
}
