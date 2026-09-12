package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.data.repository.ChangesRepository
import com.airemote.network.airemote.dto.ChangedFileDto
import com.airemote.network.airemote.dto.DiffResponse
import com.airemote.network.airemote.dto.FileContentDto
import com.airemote.network.airemote.dto.FileEntryDto
import com.airemote.network.http.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FilesMode { Changes, All }

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

data class FileBrowserUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val path: String = "",
    val parent: String? = null,
    val entries: List<FileEntryDto> = emptyList(),
    val nextCursor: String? = null,
    val showHidden: Boolean = false,
    val showIgnored: Boolean = false,
    val error: String? = null,
)

sealed class FileContentUiState {
    data class Loading(val path: String) : FileContentUiState()
    data class Ready(val content: FileContentDto) : FileContentUiState()
    data class Error(val path: String, val message: String) : FileContentUiState()
}

class FilesViewModel(
    private val repository: ChangesRepository = ChangesRepository(),
) : ViewModel() {

    private val _mode = MutableStateFlow(FilesMode.Changes)
    val mode = _mode.asStateFlow()

    private val _uiState = MutableStateFlow<FilesUiState>(FilesUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _diffState = MutableStateFlow<DiffUiState?>(null)
    val diffState = _diffState.asStateFlow()

    private val _fileBrowser = MutableStateFlow(FileBrowserUiState())
    val fileBrowser = _fileBrowser.asStateFlow()

    private val _fileContent = MutableStateFlow<FileContentUiState?>(null)
    val fileContent = _fileContent.asStateFlow()

    init {
        viewModelScope.launch {
            WorkspaceSelection.selectedId.collect { workspaceId ->
                _diffState.value = null
                _fileContent.value = null
                _uiState.value = FilesUiState.Loading
                _fileBrowser.value = FileBrowserUiState()
                loadChanges(workspaceId)
                if (_mode.value == FilesMode.All) browse(null)
            }
        }
    }

    fun refresh() {
        when (_mode.value) {
            FilesMode.Changes -> {
                viewModelScope.launch {
                    _uiState.value = FilesUiState.Loading
                    loadChanges(WorkspaceSelection.current())
                }
            }
            FilesMode.All -> browse(_fileBrowser.value.path.ifBlank { null })
        }
    }

    fun selectMode(mode: FilesMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        if (mode == FilesMode.All && _fileBrowser.value.entries.isEmpty() && !_fileBrowser.value.loading) {
            browse(null)
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

    fun browse(path: String?) {
        val current = _fileBrowser.value
        _fileBrowser.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            loadFiles(WorkspaceSelection.current(), path, cursor = null, append = false)
        }
    }

    fun loadMore() {
        val current = _fileBrowser.value
        val cursor = current.nextCursor ?: return
        if (current.loading || current.loadingMore) return
        _fileBrowser.value = current.copy(loadingMore = true, error = null)
        viewModelScope.launch {
            loadFiles(WorkspaceSelection.current(), current.path.ifBlank { null }, cursor, append = true)
        }
    }

    fun toggleShowHidden() {
        val next = !_fileBrowser.value.showHidden
        _fileBrowser.value = _fileBrowser.value.copy(showHidden = next)
        browse(_fileBrowser.value.path.ifBlank { null })
    }

    fun toggleShowIgnored() {
        val next = !_fileBrowser.value.showIgnored
        _fileBrowser.value = _fileBrowser.value.copy(showIgnored = next)
        browse(_fileBrowser.value.path.ifBlank { null })
    }

    private suspend fun loadFiles(workspaceId: String?, path: String?, cursor: String?, append: Boolean) {
        val current = _fileBrowser.value
        when (val r = repository.files(
            workspaceId = workspaceId,
            path = path,
            cursor = cursor,
            limit = 200,
            showHidden = current.showHidden,
            showIgnored = current.showIgnored,
        )) {
            is NetworkResult.Success -> {
                val entries = if (append) current.entries + r.data.entries else r.data.entries
                _fileBrowser.value = current.copy(
                    loading = false,
                    loadingMore = false,
                    path = r.data.path,
                    parent = r.data.parent,
                    entries = entries,
                    nextCursor = r.data.nextCursor,
                    error = null,
                )
            }
            is NetworkResult.Error -> {
                _fileBrowser.value = current.copy(
                    loading = false,
                    loadingMore = false,
                    error = friendly(r.code, r.message),
                )
            }
        }
    }

    fun openFile(entry: FileEntryDto) {
        if (entry.type != "file") return
        _fileContent.value = FileContentUiState.Loading(entry.path)
        viewModelScope.launch {
            when (val r = repository.fileContent(WorkspaceSelection.current(), entry.path)) {
                is NetworkResult.Success -> _fileContent.value = FileContentUiState.Ready(r.data)
                is NetworkResult.Error -> _fileContent.value = FileContentUiState.Error(entry.path, friendly(r.code, r.message))
            }
        }
    }

    fun closeFile() {
        _fileContent.value = null
    }

    private fun friendly(code: Int, message: String): String = when (code) {
        401 -> "token 无效或未授权（401）"
        -1 -> "无法连接 daemon，请检查网络与地址"
        else -> "请求失败：$message"
    }
}
