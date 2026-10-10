package com.noresign.paboot.ui.update

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
import com.noresign.paboot.viewmodel.UpdateUiState

/**
 * 更新状态机 → 弹窗。
 *
 * 两个要点：
 * 1. 整条流程只用**一个** [AlertDialog]，只按 [state] 换内容。按状态各起一个 AlertDialog 时，
 *    `发现新版本 → 正在下载` 这类切换会销毁旧窗口、新建新窗口，进出场动画叠加起来像闪屏。
 * 2. `Checking` **不弹窗**。它是转瞬即逝的中间态，先弹一个小窗、结果回来再撑大，视觉上仍是一次
 *    闪动 —— 光固定尺寸治标不治本，changelog 一长照样变。「正在检查」的反馈改放设置页按钮里
 *    （内联转圈），弹窗只负责**有结果之后**的状态。
 */
@Composable
fun UpdateHost(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is UpdateUiState.Idle || state is UpdateUiState.Checking) return

    // 下载中不可取消；其余状态允许点外部 / 返回键关闭
    val dismissible = state !is UpdateUiState.Downloading

    AlertDialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        title = {
            Text(
                when (state) {
                    is UpdateUiState.NoUpdate -> "已是最新版本"
                    is UpdateUiState.Available -> "发现新版本"
                    is UpdateUiState.Downloading -> "正在下载更新"
                    is UpdateUiState.Downloaded -> "下载完成"
                    is UpdateUiState.Error -> "更新失败"
                    else -> ""
                }
            )
        },
        text = {
            when (state) {
                is UpdateUiState.NoUpdate -> Text("当前版本：${state.currentVersionName}")
                is UpdateUiState.Available -> Column {
                    Text("版本：${state.manifest.versionName}")
                    Text("大小：${formatBytes(state.manifest.apkSize)}")
                    state.manifest.changelog?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("更新内容：", style = MaterialTheme.typography.labelLarge)
                        Text(it)
                    }
                }
                is UpdateUiState.Downloading ->
                    Text("${state.manifest.versionName} · ${downloadPercent(state)}%")
                is UpdateUiState.Downloaded -> Column {
                    Text("${state.manifest.versionName} 已下载，点击安装。")
                    if (state.apkVersionCode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "安装包：${state.apkVersionName ?: "-"}（${state.apkVersionCode}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is UpdateUiState.Error -> Text(state.message)
                else -> Unit
            }
        },
        confirmButton = {
            when (state) {
                is UpdateUiState.NoUpdate -> TextButton(onClick = onDismiss) { Text("好的") }
                is UpdateUiState.Available -> TextButton(onClick = onDownload) { Text("下载") }
                is UpdateUiState.Downloaded -> TextButton(onClick = onInstall) { Text("安装") }
                is UpdateUiState.Error -> TextButton(onClick = onRetry) { Text("重试") }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is UpdateUiState.Available -> TextButton(onClick = onDismiss) { Text("稍后") }
                is UpdateUiState.Downloaded -> TextButton(onClick = onDismiss) { Text("稍后") }
                is UpdateUiState.Error -> TextButton(onClick = onDismiss) { Text("关闭") }
                else -> Unit
            }
        },
    )
}

private fun downloadPercent(state: UpdateUiState.Downloading): Int =
    if (state.totalBytes > 0) (state.downloadedBytes * 100 / state.totalBytes).toInt() else 0

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
