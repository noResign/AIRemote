package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.data.repository.ChangesRepository
import com.airemote.network.airemote.dto.ChangedFileDto
import com.airemote.network.airemote.dto.DiffResponse
import com.airemote.network.http.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class FilesUiState {
    object Loading : FilesUiState()
    data class Content(
        val workspacePath: String,
        val isGitRepo: Boolean,
        val files: List<ChangedFileDto>,
    ) : FilesUiState()
    data class Error(val message: String) : FilesUiState()
}

sealed class DiffUiState {
    data class Loading(val path: String) : DiffUiState()
    data class Ready(val diff: DiffResponse) : DiffUiState()
    data class Error(val path: String, val message: String) : DiffUiState()
}

class FilesViewModel(
    private val repository: ChangesRepository = ChangesRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<FilesUiState>(FilesUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _diffState = MutableStateFlow<DiffUiState?>(null)
    val diffState = _diffState.asStateFlow()

    init {
        viewModelScope.launch {
            WorkspaceSelection.selectedId.collect { workspaceId ->
                _diffState.value = null
                _uiState.value = FilesUiState.Loading
                loadChanges(workspaceId)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = FilesUiState.Loading
            loadChanges(WorkspaceSelection.current())
        }
    }

    private suspend fun loadChanges(workspaceId: String?) {
        when (val r = repository.changes(workspaceId)) {
            is NetworkResult.Success -> _uiState.value = FilesUiState.Content(
                workspacePath = r.data.workspacePath,
                isGitRepo = r.data.isGitRepo,
                files = r.data.files,
            )
            is NetworkResult.Error -> _uiState.value = FilesUiState.Error(friendly(r.code, r.message))
        }
    }

    fun openDiff(file: ChangedFileDto) {
        if (file.isDirectory) return
        _diffState.value = DiffUiState.Loading(file.path)
        viewModelScope.launch {
            when (val r = repository.diff(WorkspaceSelection.current(), file.path)) {
                is NetworkResult.Success -> _diffState.value = DiffUiState.Ready(r.data)
                is NetworkResult.Error -> _diffState.value = DiffUiState.Error(file.path, friendly(r.code, r.message))
            }
        }
    }

    fun closeDiff() {
        _diffState.value = null
    }

    private fun friendly(code: Int, message: String): String = when (code) {
        401 -> "token 无效或未授权（401）"
        -1 -> "无法连接 daemon，请检查网络与地址"
        else -> "请求失败：$message"
    }
}
