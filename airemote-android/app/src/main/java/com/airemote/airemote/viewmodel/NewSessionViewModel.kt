package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.repository.MetaRepository
import com.airemote.airemote.model.agent.AgentDto
import com.airemote.airemote.model.claude.ClaudeSessionDto
import com.airemote.airemote.model.network.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class NewSessionUiState {
    object Loading : NewSessionUiState()
    data class Ready(
        val workspaces: List<String>,
        val defaultWorkspace: String,
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

    private val _selectedWorkspace = MutableStateFlow<String?>(null)
    val selectedWorkspace = _selectedWorkspace.asStateFlow()

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
            val workspaces = repository.workspaces()
            val agents = repository.agents()
            val claudeSessions = repository.claudeSessions()

            when {
                workspaces is NetworkResult.Error -> _uiState.value = NewSessionUiState.Error(friendly(workspaces.code, workspaces.message))
                agents is NetworkResult.Error -> _uiState.value = NewSessionUiState.Error(friendly(agents.code, agents.message))
                claudeSessions is NetworkResult.Error -> _uiState.value = NewSessionUiState.Error(friendly(claudeSessions.code, claudeSessions.message))
                else -> {
                    val ws = (workspaces as NetworkResult.Success).data
                    val ag = (agents as NetworkResult.Success).data
                    val cs = (claudeSessions as NetworkResult.Success).data
                    if (_selectedWorkspace.value == null) _selectedWorkspace.value = ws.default
                    if (_selectedAgent.value == null) _selectedAgent.value = ag.agents.firstOrNull()?.id
                    _uiState.value = NewSessionUiState.Ready(
                        workspaces = ws.workspaces,
                        defaultWorkspace = ws.default,
                        agents = ag.agents,
                        claudeSessions = cs.sessions,
                    )
                }
            }
        }
    }

    fun selectWorkspace(ws: String) {
        _selectedWorkspace.value = ws
        _selectedClaudeSession.value = null
    }

    fun selectAgent(id: String) {
        _selectedAgent.value = id
    }

    fun selectClaudeSession(id: String) {
        _selectedClaudeSession.value = id
        _selectedWorkspace.value = null
    }

    private fun friendly(code: Int, message: String): String = when (code) {
        401 -> "token 无效或未授权（401）"
        -1 -> "无法连接 daemon，请检查网络与地址"
        else -> "请求失败：$message"
    }
}
