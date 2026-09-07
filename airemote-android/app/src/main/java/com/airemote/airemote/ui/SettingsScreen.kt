package com.airemote.airemote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.airemote.viewmodel.SettingsUiState
import com.airemote.airemote.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onReconnect: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val baseUrl = SettingsStore.baseUrl ?: ""
    val token = SettingsStore.token ?: ""

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
            InfoRow("token", if (token.isBlank()) "未配置" else "${token.take(8)}…", monospace = true)
            OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) {
                Text("重新连接")
            }

            SectionTitle("信息")
            when (val state = uiState) {
                is SettingsUiState.Loading -> CircularProgressIndicator()
                is SettingsUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = viewModel::load) { Text("重试") }
                }
                is SettingsUiState.Ready -> {
                    InfoRow("daemon 版本", state.version)
                }
            }

            SectionTitle("关于")
            InfoRow("App 版本", "0.1.0")
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
