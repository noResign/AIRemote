package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.data.repository.MetaRepository
import com.airemote.airemote.data.repository.WorkspaceRepository
import com.airemote.airemote.util.friendlyError
import com.airemote.network.airemote.dto.AgentDto
import com.airemote.network.airemote.dto.ClaudeSessionDto
import com.airemote.network.airemote.dto.WorkspaceDto
import com.airemote.network.http.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class NewSessionUiState {
    object Loading : NewSessionUiState()
    data class Ready(
        val agents: List<AgentDto>,
        val claudeSessions: List<ClaudeSessionDto>,
        val workspaces: List<WorkspaceDto>,
    ) : NewSessionUiState()
    data class Error(val message: String) : NewSessionUiState()
}

class NewSessionViewModel(
    private val repository: MetaRepository = MetaRepository(),
    private val workspaceRepository: WorkspaceRepository = WorkspaceRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<NewSessionUiState>(NewSessionUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _selectedAgent = MutableStateFlow<String?>(null)
    val selectedAgent = _selectedAgent.asStateFlow()

    private val _selectedClaudeSession = MutableStateFlow<String?>(null)
    val selectedClaudeSession = _selectedClaudeSession.asStateFlow()

    private val _selectedWorkspaceId = MutableStateFlow(WorkspaceSelection.current())
    val selectedWorkspaceId = _selectedWorkspaceId.asStateFlow()

    private val _permissionMode = MutableStateFlow("ask")
    val permissionMode = _permissionMode.asStateFlow()

    init {
        viewModelScope.launch {
            WorkspaceSelection.selectedId.collect { workspaceId ->
                _selectedWorkspaceId.value = workspaceId
                load()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = NewSessionUiState.Loading
            val agents = repository.agents()
            val workspaces = workspaceRepository.listWorkspaces()
            if (agents is NetworkResult.Error) {
                _uiState.value = NewSessionUiState.Error(friendlyError(agents))
                return@launch
            }
            if (workspaces is NetworkResult.Error) {
                _uiState.value = NewSessionUiState.Error(friendlyError(workspaces))
                return@launch
            }

            val agentList = (agents as NetworkResult.Success).data.agents
            val workspaceList = (workspaces as NetworkResult.Success).data
            val current = _selectedWorkspaceId.value
                ?.takeIf { id -> workspaceList.any { it.id == id } }
                ?: workspaceList.firstOrNull { it.isDefault }
                    ?.id
                ?: workspaceList.firstOrNull()?.id
            _selectedWorkspaceId.value = current
            if (current != null) WorkspaceSelection.select(current, workspaceList.find { it.id == current }?.path)

            val claudeSessions = repository.claudeSessions(current)
            if (claudeSessions is NetworkResult.Error) {
                _uiState.value = NewSessionUiState.Error(friendlyError(claudeSessions))
                return@launch
            }
            val config = workspaceRepository.config()
            if (config is NetworkResult.Success) {
                _permissionMode.value = config.data.defaultPermissionMode
            }

            if (_selectedAgent.value == null) _selectedAgent.value = agentList.firstOrNull()?.id
            _uiState.value = NewSessionUiState.Ready(
                agents = agentList,
                claudeSessions = (claudeSessions as NetworkResult.Success).data.sessions,
                workspaces = workspaceList,
            )
        }
    }

    fun selectAgent(id: String) {
        _selectedAgent.value = id
    }

    fun selectClaudeSession(id: String) {
        _selectedClaudeSession.value = id
    }

    fun selectWorkspace(id: String) {
        val path = (_uiState.value as? NewSessionUiState.Ready)?.workspaces?.find { it.id == id }?.path
        _selectedClaudeSession.value = null
        // 交给 WorkspaceSelection collector 统一触发 load()。
        WorkspaceSelection.select(id, path)
    }

    fun selectPermissionMode(mode: String) {
        _permissionMode.value = mode
    }
}
