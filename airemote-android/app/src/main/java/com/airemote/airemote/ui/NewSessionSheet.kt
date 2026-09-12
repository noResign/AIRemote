package com.airemote.airemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.airemote.data.NewSessionConfig
import com.airemote.airemote.util.relativeTime
import com.airemote.airemote.viewmodel.NewSessionUiState
import com.airemote.airemote.viewmodel.NewSessionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(
    onDismiss: () -> Unit,
    onCreate: (NewSessionConfig) -> Unit,
    viewModel: NewSessionViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedAgent by viewModel.selectedAgent.collectAsState()
    val selectedClaudeSession by viewModel.selectedClaudeSession.collectAsState()
    val selectedWorkspaceId by viewModel.selectedWorkspaceId.collectAsState()
    val permissionMode by viewModel.permissionMode.collectAsState()

    // "new" 或 "resume"
    var mode by remember { mutableStateOf("new") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("新建会话", style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == "new",
                    onClick = { mode = "new" },
                    label = { Text("新建空会话") },
                )
                FilterChip(
                    selected = mode == "resume",
                    onClick = { mode = "resume" },
                    label = { Text("续接本机会话") },
                )
            }

            when (val state = uiState) {
                is NewSessionUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                is NewSessionUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = viewModel::load) { Text("重试") }
                }
                is NewSessionUiState.Ready -> {
                    if (mode == "resume") {
                        if (state.claudeSessions.isEmpty()) {
                            Text(
                                "没有可续接的本机 Claude 会话",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(state.claudeSessions, key = { it.sessionId }) { session ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectClaudeSession(session.sessionId) }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = selectedClaudeSession == session.sessionId,
                                            onClick = { viewModel.selectClaudeSession(session.sessionId) },
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                session.summary.ifBlank { "(无摘要)" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = "${session.cwd} · ${relativeTime(session.lastActiveAt)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text("Agent", style = MaterialTheme.typography.labelLarge)
                        state.agents.forEach { agent ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectAgent(agent.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedAgent == agent.id,
                                    onClick = { viewModel.selectAgent(agent.id) },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(agent.name.ifBlank { agent.id }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Text("工作区", style = MaterialTheme.typography.labelLarge)
                        state.workspaces.forEach { workspace ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectWorkspace(workspace.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedWorkspaceId == workspace.id,
                                    onClick = { viewModel.selectWorkspace(workspace.id) },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        workspace.name.ifBlank { workspace.path },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        workspace.path,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        Text("权限模式", style = MaterialTheme.typography.labelLarge)
                        listOf(
                            "ask" to "修改类操作询问",
                            "acceptEdits" to "编辑自动放行，Bash 仍询问",
                            "bypass" to "全部通过（高风险）",
                        ).forEach { (mode, desc) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectPermissionMode(mode) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = permissionMode == mode,
                                    onClick = { viewModel.selectPermissionMode(mode) },
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
                    }

                    Button(
                        onClick = {
                            val config = if (mode == "resume" && selectedClaudeSession != null) {
                                NewSessionConfig(
                                    claudeSessionId = selectedClaudeSession,
                                    workspaceId = selectedWorkspaceId,
                                    permissionMode = permissionMode,
                                )
                            } else {
                                NewSessionConfig(
                                    runtime = selectedAgent,
                                    workspaceId = selectedWorkspaceId,
                                    permissionMode = permissionMode,
                                )
                            }
                            onCreate(config)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = mode == "resume" && selectedClaudeSession != null || mode == "new",
                    ) {
                        Text(if (mode == "resume") "开始" else "创建")
                    }
                }
            }
        }
    }
}
