package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.repository.MetaRepository
import com.airemote.airemote.model.network.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SettingsUiState {
    object Loading : SettingsUiState()
    data class Ready(val version: String, val workspaces: List<String>) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}

class SettingsViewModel(
    private val repository: MetaRepository = MetaRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.Loading
            val health = repository.health()
            val workspaces = repository.workspaces()
            when {
                health is NetworkResult.Error -> _uiState.value = SettingsUiState.Error(friendly(health.code, health.message))
                workspaces is NetworkResult.Error -> _uiState.value = SettingsUiState.Error(friendly(workspaces.code, workspaces.message))
                else -> {
                    val h = (health as NetworkResult.Success).data
                    val w = (workspaces as NetworkResult.Success).data
                    _uiState.value = SettingsUiState.Ready(
                        version = h.version ?: "unknown",
                        workspaces = w.workspaces,
                    )
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
