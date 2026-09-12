package com.airemote.airemote.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.network.airemote.dto.ChangedFileDto
import com.airemote.airemote.viewmodel.DiffUiState
import com.airemote.airemote.viewmodel.FilesUiState
import com.airemote.airemote.viewmodel.FilesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val diffState by viewModel.diffState.collectAsState()
    val selectedWorkspacePath by WorkspaceSelection.selectedPath.collectAsState()

    if (diffState != null) {
        DiffScreen(state = diffState!!, onBack = viewModel::closeDiff)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("文件")
                        selectedWorkspacePath?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("刷新") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SegmentedTabs()
            when (val state = uiState) {
                is FilesUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is FilesUiState.Error -> Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = viewModel::refresh) { Text("重试") }
                }
                is FilesUiState.Content -> {
                    when {
                        !state.isGitRepo -> EmptyHint(
                            title = "当前工作区不是 Git 仓库",
                            body = "改动视图基于 git status，暂不可用。",
                        )
                        state.files.isEmpty() -> EmptyHint(
                            title = "当前没有未提交的改动",
                            body = "Agent 修改文件后会出现在这里。",
                        )
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                Text(
                                    "${state.files.size} 个文件改动 · 基于 git status",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(state.files, key = { it.path }) { file ->
                                ChangeRow(file = file, onClick = { viewModel.openDiff(file) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedTabs() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "改动",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "全部文件（后续）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyHint(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChangeRow(file: ChangedFileDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !file.isDirectory, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChangeBadge(file.status)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.path,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(statusLabel(file.status))
                    if (file.staged) append(" · 已暂存")
                    if (file.binary) append(" · 二进制")
                    if (file.oldPath != null) append(" · from ${file.oldPath}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChangeBadge(status: String) {
    val label = when (status) {
        "added" -> "A"
        "deleted" -> "D"
        "renamed" -> "R"
        "untracked" -> "?"
        "conflicted" -> "U"
        else -> "M"
    }
    val color = when (status) {
        "added" -> MaterialTheme.colorScheme.secondary
        "deleted", "conflicted" -> MaterialTheme.colorScheme.error
        "renamed" -> MaterialTheme.colorScheme.primary
        "untracked" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(end = 2.dp),
    )
}

private fun statusLabel(status: String): String = when (status) {
    "added" -> "新增"
    "deleted" -> "删除"
    "renamed" -> "重命名"
    "untracked" -> "未跟踪"
    "conflicted" -> "冲突"
    else -> "修改"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffScreen(state: DiffUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state) {
                            is DiffUiState.Loading -> state.path
                            is DiffUiState.Ready -> state.diff.path
                            is DiffUiState.Error -> state.path
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is DiffUiState.Loading -> CircularProgressIndicator()
                is DiffUiState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(20.dp),
                )
                is DiffUiState.Ready -> DiffContent(state)
            }
        }
    }
}

@Composable
private fun DiffContent(state: DiffUiState.Ready) {
    val diff = state.diff
    if (diff.binary) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂不支持预览二进制文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (diff.truncated) {
                Text(
                    "内容过大，已截断",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(diff.patch.split('\n')) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = when {
                            line.startsWith("+") && !line.startsWith("+++") -> MaterialTheme.colorScheme.secondary
                            line.startsWith("-") && !line.startsWith("---") -> MaterialTheme.colorScheme.error
                            line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}
