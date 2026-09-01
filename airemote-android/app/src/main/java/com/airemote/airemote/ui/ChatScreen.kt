package com.airemote.airemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.airemote.model.chat.ChatUiMessage
import com.airemote.airemote.model.chat.ContentBlock
import com.airemote.airemote.model.chat.TodoItem
import com.airemote.airemote.model.chat.UsageInfo
import com.airemote.airemote.model.event.NormalizedEvent
import com.airemote.airemote.viewmodel.ChatViewModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val permission by viewModel.permission.collectAsState()
    val error by viewModel.error.collectAsState()
    val title by viewModel.sessionTitle.collectAsState()
    val cwd by viewModel.sessionCwd.collectAsState()
    val runtime by viewModel.sessionRuntime.collectAsState()
    val todos by viewModel.todos.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var todoExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title ?: "新会话", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (runtime != null || cwd != null) {
                            Text(
                                text = listOfNotNull(runtime, cwd).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (todos.isNotEmpty()) {
                    TodoListPanel(
                        todos = todos,
                        running = streaming,
                        expanded = todoExpanded,
                        onToggle = { todoExpanded = !todoExpanded },
                    )
                }
                InputBar(
                    input = input,
                    streaming = streaming,
                    onInputChange = { input = it },
                    onSend = {
                        viewModel.send(input)
                        input = ""
                    },
                    onStop = viewModel::stop,
                )
            }
        },
    ) { innerPadding ->
        MessageList(
            messages = messages,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    permission?.let { req ->
        PermissionDialog(
            permission = req,
            onDecide = viewModel::decidePermission,
        )
    }
}

@Composable
private fun MessageList(messages: List<ChatUiMessage>, modifier: Modifier = Modifier) {
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
                is ChatUiMessage.Assistant -> AssistantBlock(message)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
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
private fun AssistantBlock(message: ChatUiMessage.Assistant) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.blocks.forEach { block ->
            when (block) {
                is ContentBlock.Thinking -> ThinkingBlock(block.text)
                is ContentBlock.Text -> TextBlock(block.text)
                is ContentBlock.ToolUse -> ToolCardView(block)
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

@Composable
private fun TextBlock(text: String) {
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
private fun ThinkingBlock(thinking: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = if (expanded) "▾ 思考中（点击收起）" else "▸ 思考中（点击展开）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolCardView(card: ContentBlock.ToolUse) {
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
                    card.isError -> Text("✗", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    else -> Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
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
private fun TodoListPanel(todos: List<TodoItem>, running: Boolean, expanded: Boolean, onToggle: () -> Unit) {
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
private fun TodoRow(item: TodoItem, running: Boolean) {
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

@Composable
private fun CodeBlock(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun UsageLine(usage: UsageInfo) {
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

@Composable
private fun InputBar(
    input: String,
    streaming: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (streaming) "任务运行中…" else "输入指令…") },
                maxLines = 6,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (streaming) {
                OutlinedButton(onClick = onStop) { Text("停止") }
            } else {
                FilledIconButton(onClick = onSend, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun PermissionDialog(
    permission: NormalizedEvent.PermissionRequest,
    onDecide: (String, String?) -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Claude 请求执行命令", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = permission.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                ) {
                    Text(
                        text = commandText(permission.toolInput),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Text(
                    text = "此命令可能修改文件或系统，请确认安全后再允许。120 秒内未处理将自动拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onDecide("deny", "用户拒绝") },
                        modifier = Modifier.weight(1f),
                    ) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = { onDecide("allow_all", null) },
                        modifier = Modifier.weight(1f),
                    ) { Text("允许全部") }
                    androidx.compose.material3.Button(
                        onClick = { onDecide("allow", null) },
                        modifier = Modifier.weight(1f),
                    ) { Text("允许") }
                }
            }
        }
    }
}

private fun commandText(input: kotlinx.serialization.json.JsonElement?): String {
    if (input is JsonObject) {
        val c = input["command"]
        if (c is JsonPrimitive && c.isString) return c.content
    }
    return input?.toString() ?: ""
}
