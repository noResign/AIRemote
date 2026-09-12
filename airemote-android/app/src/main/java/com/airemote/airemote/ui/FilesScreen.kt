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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.model.diff.SplitDiffKind
import com.airemote.airemote.model.diff.parseSplitDiff
import com.airemote.network.airemote.dto.ChangedFileDto
import com.airemote.network.airemote.dto.FileEntryDto
import com.airemote.airemote.viewmodel.DiffUiState
import com.airemote.airemote.viewmodel.FileBrowserUiState
import com.airemote.airemote.viewmodel.FileContentUiState
import com.airemote.airemote.viewmodel.FilesMode
import com.airemote.airemote.viewmodel.FilesUiState
import com.airemote.airemote.viewmodel.FilesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val diffState by viewModel.diffState.collectAsState()
    val fileBrowser by viewModel.fileBrowser.collectAsState()
    val fileContent by viewModel.fileContent.collectAsState()
    val selectedWorkspacePath by WorkspaceSelection.selectedPath.collectAsState()

    when {
        fileContent != null -> FileContentScreen(state = fileContent!!, onBack = viewModel::closeFile)
        diffState != null -> DiffScreen(state = diffState!!, onBack = viewModel::closeDiff)
        else -> Scaffold(
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
                SegmentedTabs(mode = mode, onSelect = viewModel::selectMode)
                when (mode) {
                    FilesMode.Changes -> ChangesContent(
                        state = uiState,
                        onRetry = viewModel::refresh,
                        onOpen = viewModel::openDiff,
                    )
                    FilesMode.All -> FileBrowserContent(
                        state = fileBrowser,
                        onBrowse = viewModel::browse,
                        onLoadMore = viewModel::loadMore,
                        onToggleHidden = viewModel::toggleShowHidden,
                        onToggleIgnored = viewModel::toggleShowIgnored,
                        onOpenFile = viewModel::openFile,
                        onRetry = viewModel::refresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentedTabs(mode: FilesMode, onSelect: (FilesMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "改动",
            style = MaterialTheme.typography.labelLarge,
            color = if (mode == FilesMode.Changes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .clickable { onSelect(FilesMode.Changes) }
                .padding(vertical = 6.dp),
        )
        Text(
            "全部文件",
            style = MaterialTheme.typography.labelLarge,
            color = if (mode == FilesMode.All) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .clickable { onSelect(FilesMode.All) }
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun ChangesContent(
    state: FilesUiState,
    onRetry: () -> Unit,
    onOpen: (ChangedFileDto) -> Unit,
) {
    when (state) {
        is FilesUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is FilesUiState.Error -> ErrorContent(state.message, onRetry)
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
                        ChangeRow(file = file, onClick = { onOpen(file) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserContent(
    state: FileBrowserUiState,
    onBrowse: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleIgnored: () -> Unit,
    onOpenFile: (FileEntryDto) -> Unit,
    onRetry: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.path.ifBlank { "/" },
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.parent != null) {
                    TextButton(onClick = { onBrowse(state.parent) }) { Text("上一级") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onToggleHidden) {
                    Text(if (state.showHidden) "隐藏隐藏项" else "显示隐藏项")
                }
                TextButton(onClick = onToggleIgnored) {
                    Text(if (state.showIgnored) "隐藏忽略项" else "显示忽略项")
                }
            }
        }

        if (state.loading && state.entries.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }

        state.error?.let { message ->
            item { ErrorContent(message, onRetry) }
        }

        items(state.entries, key = { it.path }) { entry ->
            FileEntryRow(entry = entry, onOpen = { onOpenFile(entry) }, onBrowse = onBrowse)
        }

        if (state.nextCursor != null) {
            item {
                OutlinedButton(
                    onClick = onLoadMore,
                    enabled = !state.loadingMore,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.loadingMore) "加载中…" else "加载更多") }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: FileEntryDto,
    onOpen: () -> Unit,
    onBrowse: (String?) -> Unit,
) {
    val isDirectory = entry.type == "directory"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (isDirectory) onBrowse(entry.path) else onOpen() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (isDirectory) "📁" else "📄")
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    if (isDirectory) append("目录") else append(formatBytes(entry.size ?: 0))
                    entry.modifiedAt?.let { append(" · ${formatTime(it)}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileContentScreen(state: FileContentUiState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state) {
                            is FileContentUiState.Loading -> state.path
                            is FileContentUiState.Ready -> state.content.path
                            is FileContentUiState.Error -> state.path
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
        when (state) {
            is FileContentUiState.Loading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            is FileContentUiState.Error -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { Text(state.message, color = MaterialTheme.colorScheme.error) }
            is FileContentUiState.Ready -> {
                val content = state.content
                when {
                    content.binary -> Box(
                        Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) { Text("暂不支持预览二进制文件", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        if (content.truncated) {
                            Text(
                                "内容过大，已截断",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        LazyColumn(modifier = Modifier.fillMaxSize().background(IdeBackground)) {
                            items(content.content.split('\n')) { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = IdeText,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text("重试") }
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
    var split by remember { mutableStateOf(true) }
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
                actions = {
                    TextButton(onClick = { split = !split }) {
                        Text(if (split) "统一" else "并排")
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
                is DiffUiState.Ready -> DiffContent(state = state, split = split)
            }
        }
    }
}

@Composable
private fun DiffContent(state: DiffUiState.Ready, split: Boolean) {
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
            if (split) {
                SplitDiffContent(diff.patch)
            } else {
                UnifiedDiffContent(diff.patch)
            }
        }
    }
}

@Composable
private fun UnifiedDiffContent(patch: String) {
    LazyColumn(modifier = Modifier.fillMaxSize().background(IdeBackground)) {
        items(patch.split('\n')) { line ->
            val isHunk = line.startsWith("@@")
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = diffLineColor(line),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isHunk) IdeHunkBackground else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SplitDiffContent(patch: String) {
    val rows = remember(patch) { parseSplitDiff(patch) }
    var expandedRow by remember(patch) { mutableStateOf<Int?>(null) }
    val horizontalState = rememberScrollState()
    val maxChars = remember(patch) { patch.split('\n').maxOfOrNull { it.length } ?: 0 }
    val columnWidth = remember(maxChars) { minOf(maxOf(DiffColumnWidth, (maxChars * 7).dp), 3200.dp) }
    LazyColumn(modifier = Modifier.fillMaxSize().background(IdeBackground)) {
        itemsIndexed(rows) { index, row ->
            when (row.kind) {
                SplitDiffKind.HUNK -> Text(
                    row.fullText.orEmpty(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = IdeHunk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(IdeHunkBackground)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                SplitDiffKind.META -> Text(
                    row.fullText.orEmpty(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = IdeMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
                SplitDiffKind.LINES -> {
                    val expanded = expandedRow == index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalState)
                            .clickable { expandedRow = if (expanded) null else index }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        SplitDiffCell(
                            lineNumber = row.oldLineNumber,
                            text = row.oldText,
                            color = if (row.oldText != null && row.newText == null) IdeDeleted else IdeText,
                            expanded = expanded,
                            modifier = Modifier.width(columnWidth),
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(IdeDivider)
                                .align(Alignment.CenterVertically),
                        )
                        SplitDiffCell(
                            lineNumber = row.newLineNumber,
                            text = row.newText,
                            color = if (row.newText != null && row.oldText == null) IdeAdded else IdeText,
                            expanded = expanded,
                            modifier = Modifier.width(columnWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitDiffCell(
    lineNumber: Int?,
    text: String?,
    color: Color,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Text(
            lineNumber?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = IdeMuted,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text.orEmpty(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            softWrap = expanded,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun diffLineColor(line: String): Color = when {
    line.startsWith("+") && !line.startsWith("+++") -> IdeAdded
    line.startsWith("-") && !line.startsWith("---") -> IdeDeleted
    line.startsWith("@@") -> IdeHunk
    else -> IdeText
}

private val IdeBackground = Color(0xFF1E1E1E)
private val IdeHunkBackground = Color(0xFF2D2D30)
private val IdeText = Color(0xFFD4D4D4)
private val IdeMuted = Color(0xFF858585)
private val IdeAdded = Color(0xFF89D185)
private val IdeDeleted = Color(0xFFF48771)
private val IdeHunk = Color(0xFF569CD6)
private val IdeDivider = Color(0xFF3C3C3C)
private val DiffColumnWidth = 520.dp

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun formatTime(epochMs: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
