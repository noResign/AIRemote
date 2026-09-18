package com.airemote.airemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.airemote.ui.theme.LocalSemanticColors
import com.airemote.airemote.viewmodel.SessionPermissionsUiState
import com.airemote.airemote.ui.chat.InputBar
import com.airemote.airemote.ui.chat.MessageList
import com.airemote.airemote.ui.chat.PermissionDialog
import com.airemote.airemote.ui.chat.TodoListPanel
import com.airemote.airemote.ui.chat.toolLabel
import com.airemote.airemote.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val historyLoading by viewModel.historyLoading.collectAsState()
    val permission by viewModel.permission.collectAsState()
    val error by viewModel.error.collectAsState()
    val title by viewModel.sessionTitle.collectAsState()
    val cwd by viewModel.sessionCwd.collectAsState()
    val runtime by viewModel.sessionRuntime.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val sessionPermissions by viewModel.sessionPermissions.collectAsState()
    val reconnecting by viewModel.reconnecting.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
                actions = {
                    TextButton(onClick = viewModel::openSessionPermissions) { Text("权限") }
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
                if (reconnecting) {
                    ReconnectBanner()
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
                    onMic = { scope.launch { snackbarHostState.showSnackbar("语音输入即将上线") } },
                )
            }
        },
    ) { innerPadding ->
        MessageList(
            messages = messages,
            streaming = streaming,
            loading = historyLoading,
            onAnswer = viewModel::answerQuestion,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                },
        )
    }

    permission?.let { req ->
        PermissionDialog(
            permission = req,
            onDecide = viewModel::decidePermission,
        )
    }

    sessionPermissions?.let { state ->
        ModalBottomSheet(onDismissRequest = viewModel::closeSessionPermissions) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Text("会话权限", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(12.dp))
                when (state) {
                    is SessionPermissionsUiState.Loading -> CircularProgressIndicator()
                    is SessionPermissionsUiState.Error -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::closeSessionPermissions) { Text("关闭") }
                    }
                    is SessionPermissionsUiState.Ready -> {
                        listOf(
                            "ask" to "修改类操作询问",
                            "acceptEdits" to "编辑自动放行，Bash 仍询问",
                            "bypass" to "全部通过（高风险）",
                        ).forEach { (mode, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.updateSessionPermissionMode(mode) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.mode == mode,
                                    onClick = { viewModel.updateSessionPermissionMode(mode) },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mode, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("已授权工具", style = MaterialTheme.typography.titleSmall)
                        if (state.grants.isEmpty()) {
                            Text(
                                "暂无",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            state.grants.forEach { grant ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Text(toolLabel(grant.toolName), modifier = Modifier.weight(1f))
                                    TextButton(onClick = { viewModel.revokePermissionGrant(grant.toolName) }) {
                                        Text("撤销")
                                    }
                                }
                            }
                            TextButton(onClick = viewModel::revokeAllPermissionGrants) { Text("全部撤销") }
                        }
                        Text(
                            "权限模式只对后续 Run 生效；当前运行中的 Run 不受影响。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 断线重连中的提示条（设计规范 §7.5 的 `reconnecting` 态：琥珀转圈 + 文案）。
 * run 仍在电脑上跑，所以这里不打断阅读、也不清空消息，只提示"正在续上"。
 */
@Composable
private fun ReconnectBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = LocalSemanticColors.current.warning,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "连接断开，正在重连…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
