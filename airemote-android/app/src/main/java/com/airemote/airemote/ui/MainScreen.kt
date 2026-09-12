package com.airemote.airemote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private enum class MainTab(val label: String, val icon: ImageVector) {
    Sessions("会话", Icons.Rounded.ChatBubble),
    Files("文件", Icons.Rounded.Folder),
    Settings("设置", Icons.Rounded.Settings),
}

/**
 * 主界面：底部 Tab Bar 容器（会话 / 文件 / 设置），对应 docs/ui_design.md §3。
 * 聊天详情是 push 页（在 MAIN 之上），进入后本容器（含 Tab）隐藏。
 */
@Composable
fun MainScreen(
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onReconnect: () -> Unit,
    onManageWorkspaces: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Sessions) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                MainTab.Sessions -> SessionListScreen(
                    onOpenSession = onOpenSession,
                    onNewSession = onNewSession,
                )
                MainTab.Files -> FilesScreen()
                MainTab.Settings -> SettingsScreen(onReconnect = onReconnect, onManageWorkspaces = onManageWorkspaces)
            }
        }
    }
}
