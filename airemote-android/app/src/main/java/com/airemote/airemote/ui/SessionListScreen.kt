package com.airemote.airemote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.airemote.data.PendingNewSession
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.airemote.model.session.SessionDto
import com.airemote.airemote.model.session.WorkspaceGroup
import com.airemote.airemote.ui.component.AgentBadge
import com.airemote.airemote.ui.component.RunningIndicator
import com.airemote.airemote.ui.theme.CodeBody
import com.airemote.airemote.util.relativeTime
import com.airemote.airemote.viewmodel.SessionListUiState
import com.airemote.airemote.viewmodel.SessionListViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    viewModel: SessionListViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<SessionDto?>(null) }
    var showNewSession by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshSilently()
        onPauseOrDispose { }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("会话") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewSession = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "新建会话")
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val state = uiState) {
                is SessionListUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is SessionListUiState.Error -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = viewModel::refresh) { Text("重试") }
                    }
                }
                is SessionListUiState.Content -> {
                    if (state.groups.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text("还没有会话", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { showNewSession = true }) { Text("新建会话") }
                        }
                    } else {
                        SessionGroupedList(
                            groups = state.groups,
                            onOpenSession = onOpenSession,
                            onLongClick = { pendingDelete = it },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话") },
            text = { Text("确定删除「${session.title ?: "未命名会话"}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(session.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (showNewSession) {
        NewSessionSheet(
            onDismiss = { showNewSession = false },
            onCreate = { config ->
                PendingNewSession.set(config.claudeSessionId, config.runtime)
                showNewSession = false
                onNewSession()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionGroupedList(
    groups: List<WorkspaceGroup>,
    onOpenSession: (String) -> Unit,
    onLongClick: (SessionDto) -> Unit,
) {
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    val currentWorkspace = SettingsStore.workspace

    // 默认只展开「当前 workspace」，其余分组折叠；用户手动操作后尊重其选择。
    LaunchedEffect(groups, currentWorkspace) {
        if (currentWorkspace.isNullOrBlank()) return@LaunchedEffect
        groups.forEach { group ->
            if (group.cwd != currentWorkspace && !collapsed.containsKey(group.cwd)) {
                collapsed[group.cwd] = true
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { group ->
            val isCollapsed = collapsed[group.cwd] ?: false
            stickyHeader(key = "cwd-${group.cwd}") {
                WorkspaceHeader(
                    group = group,
                    collapsed = isCollapsed,
                    onToggle = { collapsed[group.cwd] = !isCollapsed },
                )
            }
            if (!isCollapsed) {
                items(group.sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onOpenSession(session.id) },
                        onLongClick = { onLongClick(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    group: WorkspaceGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onToggle)
            .padding(top = 12.dp, bottom = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = group.cwd,
            style = CodeBody.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${group.sessions.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
            contentDescription = if (collapsed) "展开" else "收起",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: SessionDto,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val border = if (session.running) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = border,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.running) {
                    RunningIndicator()
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = session.title ?: "未命名会话",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = relativeTime(session.lastActiveAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AgentBadge(runtimeId = session.runtime)
        }
    }
}
