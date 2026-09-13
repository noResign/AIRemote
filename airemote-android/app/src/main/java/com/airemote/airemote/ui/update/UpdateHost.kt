package com.airemote.airemote.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airemote.airemote.viewmodel.UpdateUiState

@Composable
fun UpdateHost(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateUiState.Idle -> Unit
        is UpdateUiState.Checking -> {
            if (state.manual) {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("检查更新") },
                    text = { Text("正在检查新版本…") },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                )
            }
        }
        is UpdateUiState.NoUpdate -> {
            if (state.manual) {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("已是最新版本") },
                    text = { Text("当前版本：${state.currentVersionName}") },
                    confirmButton = { TextButton(onClick = onDismiss) { Text("好的") } },
                )
            }
        }
        is UpdateUiState.Available -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("发现新版本") },
                text = {
                    Column {
                        Text("版本：${state.manifest.versionName}")
                        Text("大小：${formatBytes(state.manifest.apkSize)}")
                        state.manifest.changelog?.takeIf { it.isNotBlank() }?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("更新内容：", style = MaterialTheme.typography.labelLarge)
                            Text(it)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDownload) { Text("下载") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
            )
        }
        is UpdateUiState.Downloading -> {
            val percent = if (state.totalBytes > 0) {
                (state.downloadedBytes * 100 / state.totalBytes).toInt()
            } else {
                0
            }
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在下载更新") },
                text = {
                    Text("${state.manifest.versionName} · $percent%")
                },
                confirmButton = {},
            )
        }
        is UpdateUiState.Downloaded -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("下载完成") },
                text = { Text("${state.manifest.versionName} 已下载，点击安装。") },
                confirmButton = { TextButton(onClick = onInstall) { Text("安装") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
            )
        }
        is UpdateUiState.Error -> {
            if (state.manual) {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("更新失败") },
                    text = { Text(state.message) },
                    confirmButton = { TextButton(onClick = onRetry) { Text("重试") } },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
