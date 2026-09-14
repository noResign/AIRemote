package com.airemote.airemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.airemote.BuildConfig
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.airemote.viewmodel.DirectoryPickerUiState
import com.airemote.airemote.viewmodel.SettingsUiState
import com.airemote.airemote.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onReconnect: () -> Unit,
    onManageWorkspaces: () -> Unit,
    onCheckUpdate: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedWorkspaceId by viewModel.selectedWorkspaceId.collectAsState()
    val baseUrl = SettingsStore.baseUrl ?: ""
    val token = SettingsStore.token ?: ""
    val daemonVersion = (uiState as? SettingsUiState.Ready)?.version

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionTitle("连接")
            InfoRow("服务器地址", baseUrl.ifBlank { "未配置" }, monospace = true)
            InfoRow("token", if (token.isBlank()) "未配置" else "••••••••")
            OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                Text("重新连接")
            }

            when (val state = uiState) {
                is SettingsUiState.Loading -> CircularProgressIndicator()
                is SettingsUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = viewModel::load) { Text("重试") }
                }
                is SettingsUiState.Ready -> {
                    SectionTitle("工作区")
                    val currentWorkspace = state.workspaces.find { it.id == selectedWorkspaceId }
                    InfoRow(
                        "当前工作区",
                        currentWorkspace?.path ?: "未选择",
                        monospace = true,
                    )
                    OutlinedButton(
                        onClick = onManageWorkspaces,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("管理工作区") }

                    SectionTitle("新会话默认权限")
                    listOf(
                        "ask" to "修改类操作询问",
                        "acceptEdits" to "编辑自动放行，Bash 仍询问",
                        "bypass" to "全部通过（高风险）",
                    ).forEach { (mode, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setDefaultPermissionMode(mode) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.defaultPermissionMode == mode,
                                onClick = { viewModel.setDefaultPermissionMode(mode) },
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

                    SectionTitle("客户端更新")
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("检查更新") }
                }
            }

            SectionTitle("关于")
            InfoRow("App 版本", BuildConfig.VERSION_NAME)
            InfoRow("daemon 版本", daemonVersion ?: "未知")
        }
    }

}

@Composable
internal fun DirectoryPickerDialog(
    state: DirectoryPickerUiState,
    onDismiss: () -> Unit,
    onBrowse: (String) -> Unit,
    onToggleHidden: () -> Unit,
    onSelect: () -> Unit,
) {
    when (state) {
        is DirectoryPickerUiState.Loading -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("选择工作区目录") },
                text = { CircularProgressIndicator() },
                confirmButton = {},
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            )
        }
        is DirectoryPickerUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("目录加载失败") },
                text = { Text(state.message, color = MaterialTheme.colorScheme.error) },
                confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            )
        }
        is DirectoryPickerUiState.Ready -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("选择工作区目录") },
                text = {
                    Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            state.path,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.parent?.let { parent ->
                            TextButton(onClick = { onBrowse(parent) }) { Text("返回上一级") }
                        }
                        TextButton(onClick = onToggleHidden) {
                            Text(if (state.showHidden) "隐藏隐藏目录" else "显示隐藏目录")
                        }
                        state.entries.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onBrowse(entry.path) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "📁 ${entry.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (entry.isWorkspace) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "已是工作区",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onSelect, enabled = !state.isWorkspace) {
                        Text(if (state.isWorkspace) "已是工作区" else "选择当前文件夹")
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (monospace) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            maxLines = if (monospace) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
