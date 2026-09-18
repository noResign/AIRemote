package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.data.repository.ChangesRepository
import com.airemote.airemote.util.friendlyError
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
        /** 本次检查的目录（workspace 相对路径，"" = 工作区根目录）。 */
        val scopePath: String,
        val isGitRepo: Boolean,
        /** `scopePath` 直接子目录里的仓库；非仓库目录下才有值。 */
        val repos: List<String>,
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

    /** 改动视图当前检查的目录（workspace 相对路径，"" = 工作区根目录）。 */
    private val _scope = MutableStateFlow("")
    val scope = _scope.asStateFlow()

    /** 选目录模式：借用 `fileBrowser` 的列表与导航，点行只用于浏览，选中靠确认按钮。 */
    private val _pickingDir = MutableStateFlow(false)
    val pickingDir = _pickingDir.asStateFlow()

    init {
        viewModelScope.launch {
            WorkspaceSelection.selectedId.collect { workspaceId ->
                _diffState.value = null
                _fileContent.value = null
                _uiState.value = FilesUiState.Loading
                _fileBrowser.value = FileBrowserUiState()
                _scope.value = ""
                _pickingDir.value = false
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
        _pickingDir.value = false
        if (mode == FilesMode.All && _fileBrowser.value.entries.isEmpty() && !_fileBrowser.value.loading) {
            browse(null)
        }
    }

    private suspend fun loadChanges(workspaceId: String?, dir: String = _scope.value) {
        when (val r = repository.changes(workspaceId, dir)) {
            is NetworkResult.Success -> {
                _scope.value = r.data.dir
                _uiState.value = FilesUiState.Content(
                    workspacePath = r.data.workspacePath,
                    scopePath = r.data.dir,
                    isGitRepo = r.data.isGitRepo,
                    repos = r.data.repos,
                    files = r.data.files,
                )
            }
            is NetworkResult.Error -> _uiState.value = FilesUiState.Error(friendlyError(r))
        }
    }

    /** 切到某个目录看它的 git（"" = 工作区根目录）。 */
    fun selectScope(dir: String) {
        _pickingDir.value = false
        _diffState.value = null
        _fileContent.value = null
        _scope.value = dir
        _uiState.value = FilesUiState.Loading
        viewModelScope.launch { loadChanges(WorkspaceSelection.current(), dir) }
    }

    fun startPickDir() {
        _pickingDir.value = true
        browse(_scope.value.ifBlank { null })
    }

    fun cancelPickDir() {
        _pickingDir.value = false
    }

    fun confirmPickDir() {
        selectScope(_fileBrowser.value.path)
    }

    fun openDiff(file: ChangedFileDto) {
        if (file.isDirectory) return
        _diffState.value = DiffUiState.Loading(file.path)
        viewModelScope.launch {
            when (val r = repository.diff(WorkspaceSelection.current(), file.path, _scope.value)) {
                is NetworkResult.Success -> _diffState.value = DiffUiState.Ready(r.data)
                is NetworkResult.Error -> _diffState.value = DiffUiState.Error(file.path, friendlyError(r))
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
                    error = friendlyError(r),
                )
            }
        }
    }

    fun openFile(entry: FileEntryDto) {
        if (entry.type != "file") return
        openFileContent(entry.path)
    }

    fun openDiffFileContent() {
        val path = (_diffState.value as? DiffUiState.Ready)?.diff?.path ?: return
        openFileContent(path)
    }

    private fun openFileContent(path: String) {
        _fileContent.value = FileContentUiState.Loading(path)
        viewModelScope.launch {
            when (val r = repository.fileContent(WorkspaceSelection.current(), path)) {
                is NetworkResult.Success -> _fileContent.value = FileContentUiState.Ready(r.data)
                is NetworkResult.Error -> _fileContent.value = FileContentUiState.Error(path, friendlyError(r))
            }
        }
    }

    fun closeFile() {
        _fileContent.value = null
    }

}
