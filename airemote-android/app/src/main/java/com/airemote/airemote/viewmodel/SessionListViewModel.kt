package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.repository.SessionRepository
import com.airemote.airemote.model.network.NetworkResult
import com.airemote.airemote.model.session.SessionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SessionListUiState {
    object Loading : SessionListUiState()
    data class Content(val sessions: List<SessionDto>) : SessionListUiState()
    data class Error(val message: String) : SessionListUiState()
}

class SessionListViewModel(
    private val repository: SessionRepository = SessionRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<SessionListUiState>(SessionListUiState.Loading)
    val uiState = _uiState.asStateFlow()

    /** Transient message (e.g. delete failure), cleared after being shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = SessionListUiState.Loading
            when (val r = repository.listSessions()) {
                is NetworkResult.Success -> _uiState.value = SessionListUiState.Content(r.data)
                is NetworkResult.Error -> _uiState.value = SessionListUiState.Error(friendlyMessage(r.code, r.message))
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            when (val r = repository.deleteSession(id)) {
                is NetworkResult.Success -> {
                    // Optimistically drop it from the current list, then re-sync.
                    val current = _uiState.value
                    if (current is SessionListUiState.Content) {
                        _uiState.value = SessionListUiState.Content(current.sessions.filterNot { it.id == id })
                    }
                    refresh()
                }
                is NetworkResult.Error -> {
                    _message.value = friendlyMessage(r.code, r.message)
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun friendlyMessage(code: Int, message: String): String = when (code) {
        401 -> "token 无效或未授权（401），请重新连接"
        -1 -> "无法连接 daemon，请检查网络与地址"
        else -> "操作失败：$message"
    }
}
