package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.data.repository.SessionRepository
import com.airemote.network.http.NetworkResult
import com.airemote.airemote.model.session.SessionListModel
import com.airemote.airemote.model.session.groupByCwd
import com.airemote.airemote.util.friendlyError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SessionListUiState {
    object Loading : SessionListUiState()
    data class Content(val model: SessionListModel) : SessionListUiState()
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
        viewModelScope.launch {
            WorkspaceSelection.selectedId.collect { workspaceId ->
                _uiState.value = SessionListUiState.Loading
                fetch(workspaceId)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = SessionListUiState.Loading
            fetch(WorkspaceSelection.current())
        }
    }

    /** Re-fetch without flashing the Loading spinner; keeps current list visible on failure. */
    fun refreshSilently() {
        viewModelScope.launch {
            fetchFor(
                WorkspaceSelection.current(),
                onError = { error ->
                    if (_uiState.value !is SessionListUiState.Content) {
                        _uiState.value = SessionListUiState.Error(friendlyError(error))
                    } else {
                        _message.value = friendlyError(error)
                    }
                },
            )
        }
    }

    private suspend fun fetch(workspaceId: String?, onError: (NetworkResult.Error) -> Unit = { error ->
        _uiState.value = SessionListUiState.Error(friendlyError(error))
    }) {
        when (val r = repository.listSessions(workspaceId)) {
            is NetworkResult.Success -> _uiState.value = SessionListUiState.Content(
                groupByCwd(r.data, WorkspaceSelection.selectedPath.value),
            )
            is NetworkResult.Error -> onError(r)
        }
    }

    private suspend fun fetchFor(workspaceId: String?, onError: (NetworkResult.Error) -> Unit) = fetch(workspaceId, onError)

    fun delete(id: String) {
        viewModelScope.launch {
            when (val r = repository.deleteSession(id)) {
                is NetworkResult.Success -> {
                    // Optimistically drop it from the current list, then re-sync.
                    val current = _uiState.value
                    if (current is SessionListUiState.Content) {
                        val all = current.model.rootSessions + current.model.subGroups.flatMap { it.sessions }
                        val remaining = all.filterNot { it.id == id }
                        _uiState.value = SessionListUiState.Content(
                            groupByCwd(remaining, WorkspaceSelection.selectedPath.value),
                        )
                    }
                    refresh()
                }
                is NetworkResult.Error -> {
                    _message.value = friendlyError(r)
                }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
