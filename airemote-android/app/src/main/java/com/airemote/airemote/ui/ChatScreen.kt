package com.airemote.airemote.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.airemote.ui.chat.InputBar
import com.airemote.airemote.ui.chat.MessageList
import com.airemote.airemote.ui.chat.PermissionDialog
import com.airemote.airemote.ui.chat.TodoListPanel
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
    val permission by viewModel.permission.collectAsState()
    val error by viewModel.error.collectAsState()
    val title by viewModel.sessionTitle.collectAsState()
    val cwd by viewModel.sessionCwd.collectAsState()
    val runtime by viewModel.sessionRuntime.collectAsState()
    val todos by viewModel.todos.collectAsState()

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
                    onMic = { scope.launch { snackbarHostState.showSnackbar("语音输入即将上线") } },
                )
            }
        },
    ) { innerPadding ->
        MessageList(
            messages = messages,
            streaming = streaming,
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
}
