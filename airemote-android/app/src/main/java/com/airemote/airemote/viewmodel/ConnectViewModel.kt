package com.airemote.airemote.viewmodel

import androidx.lifecycle.viewModelScope
import com.airemote.airemote.base.BaseViewModel
import com.airemote.airemote.data.SavedConnection
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.airemote.data.repository.ConnectRepository
import com.airemote.airemote.data.repository.WorkspaceRepository
import com.airemote.airemote.model.connect.ConnectResponse
import com.airemote.airemote.util.friendlyError
import com.airemote.network.http.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ConnectUiState {
    object Idle : ConnectUiState()
    object Connecting : ConnectUiState()
    data class Success(val data: ConnectResponse, val baseUrl: String) : ConnectUiState()
    data class Error(val message: String) : ConnectUiState()
}

class ConnectViewModel(
    private val repository: ConnectRepository = ConnectRepository(),
    private val workspaceRepository: WorkspaceRepository = WorkspaceRepository(),
    private val settings: SettingsStore = SettingsStore,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _name = MutableStateFlow(settings.name ?: "")
    val name = _name.asStateFlow()

    private val _baseUrl = MutableStateFlow(settings.baseUrl ?: "")
    val baseUrl = _baseUrl.asStateFlow()

    private val _token = MutableStateFlow(settings.token ?: "")
    val token = _token.asStateFlow()

    private val _savedConnections = MutableStateFlow(settings.savedConnections)
    val savedConnections = _savedConnections.asStateFlow()

    /** 选中「最近连接」里的某条：回填后直接发起连接。 */
    fun selectSaved(connection: SavedConnection) {
        fillSaved(connection)
        connect()
    }

    /** 名称下拉框选中某条：只回填三个输入框，不自动连接，方便先改再连。 */
    fun fillSaved(connection: SavedConnection) {
        _name.value = connection.name
        _baseUrl.value = connection.baseUrl
        _token.value = connection.token
        _uiState.value = ConnectUiState.Idle
    }

    fun forgetSaved(baseUrl: String) {
        settings.forgetConnection(baseUrl)
        _savedConnections.value = settings.savedConnections
    }

    fun onNameChange(value: String) {
        _name.value = value
        if (_uiState.value !is ConnectUiState.Connecting) {
            _uiState.value = ConnectUiState.Idle
        }
    }

    fun onBaseUrlChange(value: String) {
        _baseUrl.value = value
        if (_uiState.value !is ConnectUiState.Connecting) {
            _uiState.value = ConnectUiState.Idle
        }
    }

    fun onTokenChange(value: String) {
        _token.value = value
        if (_uiState.value !is ConnectUiState.Connecting) {
            _uiState.value = ConnectUiState.Idle
        }
    }

    fun connect() {
        val nm = _name.value.trim()
        val url = _baseUrl.value.trim()
        val tk = _token.value.trim()
        when {
            url.isEmpty() -> {
                _uiState.value = ConnectUiState.Error("请输入 daemon 地址")
                return
            }
            tk.isEmpty() -> {
                _uiState.value = ConnectUiState.Error("请输入 token")
                return
            }
        }

        _uiState.value = ConnectUiState.Connecting
        viewModelScope.launch {
            when (val result = repository.testConnection(url, tk)) {
                is NetworkResult.Success -> {
                    settings.name = nm
                    settings.baseUrl = url
                    settings.token = tk
                    settings.workspace = result.data.workspace
                    settings.rememberConnection(nm, url, tk)
                    _savedConnections.value = settings.savedConnections
                    val workspaces = workspaceRepository.listWorkspaces()
                    if (workspaces is NetworkResult.Success) {
                        val list = workspaces.data
                        val selected = settings.selectedWorkspaceId
                            ?.takeIf { id -> list.any { it.id == id } }
                            ?: list.firstOrNull { it.isDefault }?.id
                            ?: list.firstOrNull()?.id
                        WorkspaceSelection.select(selected, list.find { it.id == selected }?.path)
                    } else {
                        // 旧 daemon 可能没有 /api/workspaces：清掉本地选择，回退到“不过滤会话”。
                        WorkspaceSelection.select(null)
                    }
                    _uiState.value = ConnectUiState.Success(result.data, url)
                }
                is NetworkResult.Error -> {
                    _uiState.value = ConnectUiState.Error(friendlyError(result, fallbackPrefix = "连接失败"))
                }
            }
        }
    }
}
