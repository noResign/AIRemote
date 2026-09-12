package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.data.repository.MetaRepository
import com.airemote.airemote.data.repository.WorkspaceRepository
import com.airemote.network.airemote.dto.DirectoryEntryDto
import com.airemote.network.airemote.dto.UpdateConfigRequest
import com.airemote.network.airemote.dto.WorkspaceDto
import com.airemote.network.http.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DirectoryPickerUiState {
    data class Loading(val path: String?) : DirectoryPickerUiState()
    data class Ready(
        val path: String,
        val parent: String?,
        val entries: List<DirectoryEntryDto>,
        val showHidden: Boolean,
    ) : DirectoryPickerUiState()
    data class Error(val message: String) : DirectoryPickerUiState()
}

sealed class SettingsUiState {
    object Loading : SettingsUiState()
    data class Ready(
        val version: String,
        val workspaces: List<WorkspaceDto>,
        val defaultWorkspaceId: String?,
        val defaultPermissionMode: String,
    ) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}

class SettingsViewModel(
    private val repository: MetaRepository = MetaRepository(),
    private val workspaceRepository: WorkspaceRepository = WorkspaceRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _selectedWorkspaceId = MutableStateFlow(WorkspaceSelection.current())
    val selectedWorkspaceId = _selectedWorkspaceId.asStateFlow()

    private val _directoryPicker = MutableStateFlow<DirectoryPickerUiState?>(null)
    val directoryPicker = _directoryPicker.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.Loading
            val health = repository.health()
            if (health is NetworkResult.Error) {
                _uiState.value = SettingsUiState.Error(friendly(health.code, health.message))
                return@launch
            }
            val workspaces = workspaceRepository.listWorkspaces()
            if (workspaces is NetworkResult.Error) {
                _uiState.value = SettingsUiState.Error(friendly(workspaces.code, workspaces.message))
                return@launch
            }
            val config = workspaceRepository.config()
            if (config is NetworkResult.Error) {
                _uiState.value = SettingsUiState.Error(friendly(config.code, config.message))
                return@launch
            }
            val workspaceList = (workspaces as NetworkResult.Success).data
            val configData = (config as NetworkResult.Success).data
            val selected = _selectedWorkspaceId.value
                ?.takeIf { id -> workspaceList.any { it.id == id } }
                ?: configData.defaultWorkspaceId
                    ?.takeIf { id -> workspaceList.any { it.id == id } }
                ?: workspaceList.firstOrNull { it.isDefault }?.id
                ?: workspaceList.firstOrNull()?.id
            _selectedWorkspaceId.value = selected
            WorkspaceSelection.select(selected, workspaceList.find { it.id == selected }?.path)
            _uiState.value = SettingsUiState.Ready(
                version = (health as NetworkResult.Success).data.version ?: "unknown",
                workspaces = workspaceList,
                defaultWorkspaceId = configData.defaultWorkspaceId,
                defaultPermissionMode = configData.defaultPermissionMode,
            )
        }
    }

    fun selectWorkspace(workspaceId: String) {
        val workspace = (_uiState.value as? SettingsUiState.Ready)?.workspaces?.find { it.id == workspaceId }
        _selectedWorkspaceId.value = workspaceId
        WorkspaceSelection.select(workspaceId, workspace?.path)
    }

    fun setDefaultWorkspace(workspaceId: String) {
        viewModelScope.launch {
            when (val r = workspaceRepository.setDefault(workspaceId)) {
                is NetworkResult.Success -> {
                    _selectedWorkspaceId.value = workspaceId
                    load()
                }
                is NetworkResult.Error -> _uiState.value = SettingsUiState.Error(friendly(r.code, r.message))
            }
        }
    }

    fun setDefaultPermissionMode(mode: String) {
        viewModelScope.launch {
            when (val r = workspaceRepository.updateConfig(UpdateConfigRequest(defaultPermissionMode = mode))) {
                is NetworkResult.Success -> load()
                is NetworkResult.Error -> _uiState.value = SettingsUiState.Error(friendly(r.code, r.message))
            }
        }
    }

    fun renameWorkspace(workspaceId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            when (val r = workspaceRepository.updateWorkspace(workspaceId, name = trimmed)) {
                is NetworkResult.Success -> load()
                is NetworkResult.Error -> _uiState.value = SettingsUiState.Error(friendly(r.code, r.message))
            }
        }
    }

    fun setWorkspaceEnabled(workspaceId: String, enabled: Boolean) {
        viewModelScope.launch {
            when (val r = workspaceRepository.updateWorkspace(workspaceId, enabled = enabled)) {
                is NetworkResult.Success -> load()
                is NetworkResult.Error -> _uiState.value = SettingsUiState.Error(friendly(r.code, r.message))
            }
        }
    }

    fun deleteWorkspace(workspaceId: String) {
        viewModelScope.launch {
            when (val r = workspaceRepository.deleteWorkspace(workspaceId)) {
                is NetworkResult.Success -> {
                    if (WorkspaceSelection.current() == workspaceId) WorkspaceSelection.select(null)
                    load()
                }
                is NetworkResult.Error -> _uiState.value = SettingsUiState.Error(friendly(r.code, r.message))
            }
        }
    }

    fun openDirectoryPicker() {
        loadDirectories(null, false)
    }

    fun closeDirectoryPicker() {
        _directoryPicker.value = null
    }

    fun loadDirectories(path: String?, showHidden: Boolean = false) {
        viewModelScope.launch {
            _directoryPicker.value = DirectoryPickerUiState.Loading(path)
            when (val r = workspaceRepository.directories(path, showHidden)) {
                is NetworkResult.Success -> {
                    _directoryPicker.value = DirectoryPickerUiState.Ready(
                        path = r.data.path,
                        parent = r.data.parent,
                        entries = r.data.entries,
                        showHidden = showHidden,
                    )
                }
                is NetworkResult.Error -> _directoryPicker.value = DirectoryPickerUiState.Error(friendly(r.code, r.message))
            }
        }
    }

    fun toggleDirectoryHidden() {
        val current = _directoryPicker.value as? DirectoryPickerUiState.Ready ?: return
        loadDirectories(current.path, !current.showHidden)
    }

    fun createWorkspaceFromCurrentDirectory() {
        val current = _directoryPicker.value as? DirectoryPickerUiState.Ready ?: return
        viewModelScope.launch {
            when (val r = workspaceRepository.createWorkspace(current.path)) {
                is NetworkResult.Success -> {
                    _selectedWorkspaceId.value = r.data.id
                    WorkspaceSelection.select(r.data.id, r.data.path)
                    _directoryPicker.value = null
                    load()
                }
                is NetworkResult.Error -> {
                    _directoryPicker.value = DirectoryPickerUiState.Error(friendly(r.code, r.message))
                }
            }
        }
    }

    private fun friendly(code: Int, message: String): String = when (code) {
        401 -> "token 无效或未授权（401）"
        -1 -> "无法连接 daemon，请检查网络与地址"
        else -> "请求失败：$message"
    }
}
