package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.repository.MetaRepository
import com.airemote.network.airemote.dto.AgentDto
import com.airemote.network.airemote.dto.ClaudeSessionDto
import com.airemote.network.http.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class NewSessionUiState {
    object Loading : NewSessionUiState()
    data class Ready(
        val agents: List<AgentDto>,
        val claudeSessions: List<ClaudeSessionDto>,
    ) : NewSessionUiState()
    data class Error(val message: String) : NewSessionUiState()
}

class NewSessionViewModel(
    private val repository: MetaRepository = MetaRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<NewSessionUiState>(NewSessionUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent = _selectedAgent.asStateFlow()

    private val _selectedClaudeSession = MutableStateFlow<String?>(null)
    val selectedClaudeSession = _selectedClaudeSession.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = NewSessionUiState.Loading
            val agents = repository.agents()
            val claudeSessions = repository.claudeSessions()

            when {
                agents is NetworkResult.Error -> _uiState.value = NewSessionUiState.Error(friendly(agents.code, agents.message))
                claudeSessions is NetworkResult.Error -> _uiState.value = NewSessionUiState.Error(friendly(claudeSessions.code, claudeSessions.message))
                else -> {
                    val ag = (agents as NetworkResult.Success).data
                    val cs = (claudeSessions as NetworkResult.Success).data
                    if (_selectedAgent.value == null) _selectedAgent.value = ag.agents.firstOrNull()?.id
                    _uiState.value = NewSessionUiState.Ready(
                        agents = ag.agents,
                        claudeSessions = cs.sessions,
                    )
                }
            }
        }
    }

    fun selectAgent(id: String) {
        _selectedAgent.value = id
    }

    fun selectClaudeSession(id: String) {
        _selectedClaudeSession.value = id
    }

    private fun friendly(code: Int, message: String): String = when (code) {
        401 -> "token 无效或未授权（401）"
        -1 -> "无法连接 daemon，请检查网络与地址"
        else -> "请求失败：$message"
    }
}
