package com.noresign.paboot.data

import com.noresign.paboot.data.local.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局的「当前选中 Workspace」。
 *
 * 它是客户端本地状态，不写回 daemon；Sessions / Files / Settings 都监听它并在切换后刷新。
 */
object WorkspaceSelection {

    private val _selectedId = MutableStateFlow(SettingsStore.selectedWorkspaceId)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _selectedPath = MutableStateFlow(SettingsStore.selectedWorkspacePath)
    val selectedPath: StateFlow<String?> = _selectedPath.asStateFlow()

    fun select(workspaceId: String?, path: String? = null) {
        val normalized = workspaceId?.takeIf { it.isNotBlank() }
        SettingsStore.selectedWorkspaceId = normalized
        _selectedId.value = normalized
        if (normalized == null) {
            SettingsStore.selectedWorkspacePath = null
            _selectedPath.value = null
        } else if (path != null) {
            SettingsStore.selectedWorkspacePath = path
            _selectedPath.value = path
        }
    }

    fun current(): String? = _selectedId.value
}
