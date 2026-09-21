package com.airemote.airemote.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.network.airemote.dto.WorkspaceDto
import com.airemote.airemote.viewmodel.SettingsUiState
import com.airemote.airemote.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceManagementScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedWorkspaceId by viewModel.selectedWorkspaceId.collectAsState()
    val directoryPicker by viewModel.directoryPicker.collectAsState()
    val dirTargetWorkspaceId by viewModel.dirTargetWorkspaceId.collectAsState()

    var renameTarget by remember { mutableStateOf<WorkspaceDto?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<WorkspaceDto?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工作区") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 方法引用不套用默认参数，这里必须用 lambda 才能落到「新增工作区」。
                    TextButton(onClick = { viewModel.openDirectoryPicker() }) { Text("+ 新增") }
                },
            )
        },
    ) { innerPadding ->
        when (val state = uiState) {
            is SettingsUiState.Loading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is SettingsUiState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::load) { Text("重试") }
            }
            is SettingsUiState.Ready -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.workspaces, key = { it.id }) { workspace ->
                        WorkspaceCard(
                            workspace = workspace,
                            selected = workspace.id == selectedWorkspaceId,
                            onSelect = { viewModel.selectWorkspace(workspace.id) },
                            onSetDefault = { viewModel.setDefaultWorkspace(workspace.id) },
                            onToggleEnabled = { viewModel.setWorkspaceEnabled(workspace.id, !workspace.enabled) },
                            onRename = {
                                renameTarget = workspace
                                renameText = workspace.name
                            },
                            onDelete = { deleteTarget = workspace },
                            onAddDir = { viewModel.openDirectoryPicker(workspace.id) },
                            onRemoveDir = { path -> viewModel.removeWorkspaceDir(workspace.id, path) },
                        )
                    }
                }
            }
        }
    }

    directoryPicker?.let { state ->
        // 落点是「附加目录」时不禁用「已是工作区」——同一个目录也可以被工作区引用。
        val addingDir = dirTargetWorkspaceId != null
        DirectoryPickerDialog(
            state = state,
            onDismiss = viewModel::closeDirectoryPicker,
            onBrowse = { viewModel.loadDirectories(it) },
            onToggleHidden = viewModel::toggleDirectoryHidden,
            onSelect = viewModel::confirmDirectoryPick,
            title = if (addingDir) "选择附加目录" else "选择工作区目录",
            selectLabel = if (addingDir) "添加此文件夹" else null,
            selectEnabled = if (addingDir) true else null,
        )
    }

    renameTarget?.let { workspace ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名工作区") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameWorkspace(workspace.id, renameText)
                        renameTarget = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { workspace ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除工作区") },
            text = {
                Text("确定删除「${workspace.name.ifBlank { workspace.path }}」吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteWorkspace(workspace.id)
                        deleteTarget = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: WorkspaceDto,
    selected: Boolean,
    onSelect: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddDir: () -> Unit,
    onRemoveDir: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            workspace.name.ifBlank { workspace.path },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (workspace.isDefault) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "默认",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (!workspace.enabled) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "已禁用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Text(
                        workspace.path,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${workspace.sessionCount} 个会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 附加目录是这个工作区所有会话共用的允许列表；聊天里批准越界读取
                    // 也会写到这里，所以这里既是查看处也是撤销处。
                    workspace.dirs.forEach { dir ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "＋ $dir",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onRemoveDir(dir) }) { Text("移除") }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onSetDefault, enabled = !workspace.isDefault) { Text("设为默认") }
                TextButton(onClick = onToggleEnabled) { Text(if (workspace.enabled) "禁用" else "启用") }
                TextButton(onClick = onRename) { Text("重命名") }
                TextButton(onClick = onAddDir) { Text("+ 附加目录") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
