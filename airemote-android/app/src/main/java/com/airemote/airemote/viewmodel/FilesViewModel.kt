package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.WorkspaceSelection
import com.airemote.airemote.data.repository.ChangesRepository
import com.airemote.airemote.data.repository.WorkspaceRepository
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
    /** 当前根由 `root` StateFlow 持有，UI 显示用的是它，所以这里不再重复带一份。 */
    data class Content(
        val isGitRepo: Boolean,
        /** `root` 直接子目录里的仓库（相对当前根）；非仓库目录下才有值。 */
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
    private val workspaceRepository: WorkspaceRepository = WorkspaceRepository(),
) : ViewModel() {

    private val _mode = MutableStateFlow(FilesMode.Changes)
    val mode = _mode.asStateFlow()

    /**
     * 当前浏览/检查的根目录；null = 工作区主目录。
     *
     * 两个 Tab 的相对路径都以它为基准，所以它可以是工作区之外的任意目录
     * （daemon 侧 `/api/files`、`/api/changes` 的 `root` 参数）。
     */
    private val _root = MutableStateFlow<String?>(null)
    val root = _root.asStateFlow()

    /** 可一键切换的根：工作区主目录 + 它的附加目录。 */
    private val _knownRoots = MutableStateFlow<List<String>>(emptyList())
    val knownRoots = _knownRoots.asStateFlow()

    /** 选根对话框：走绝对路径浏览（`/api/fs/directories`），因此能到工作区之外。 */
    private val _rootPicker = MutableStateFlow<DirectoryPickerUiState?>(null)
    val rootPicker = _rootPicker.asStateFlow()
    private var rootPickerHidden = false

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
                // 换工作区就回到它的主目录，否则会拿着上一个工作区的根不放。
                _root.value = null
                _rootPicker.value = null
                loadKnownRoots(workspaceId)
                loadChanges(workspaceId)
                if (_mode.value == FilesMode.All) browse(null)
            }
        }
    }

    private suspend fun loadKnownRoots(workspaceId: String?) {
        val workspaces = when (val r = workspaceRepository.listWorkspaces()) {
            is NetworkResult.Success -> r.data
            is NetworkResult.Error -> return
        }
        val selected = workspaces.firstOrNull { it.id == workspaceId } ?: return
        _knownRoots.value = listOf(selected.path) + selected.dirs
    }

    /** 切根：改动列表与文件路径都是相对根的，所以一并重置。 */
    fun selectRoot(root: String?) {
        val normalized = root?.takeIf { it.isNotBlank() }
        if (_root.value == normalized) return
        _root.value = normalized
        _diffState.value = null
        _fileContent.value = null
        _uiState.value = FilesUiState.Loading
        _fileBrowser.value = FileBrowserUiState()
        viewModelScope.launch {
            loadChanges(WorkspaceSelection.current())
            if (_mode.value == FilesMode.All) browse(null)
        }
    }

    /**
     * 从「此目录不是 Git 仓库」下的仓库列表进入某个仓库：把当前根换成它。
     * 相对路径拼成绝对路径，于是不再需要单独的「作用域」概念。
     */
    fun openChildDir(relativePath: String) {
        val base = _root.value ?: WorkspaceSelection.selectedPath.value ?: return
        selectRoot(if (base.endsWith("/")) "$base$relativePath" else "$base/$relativePath")
    }

    fun openRootPicker() {
        rootPickerHidden = false
        loadRootDirs(_root.value, false)
    }

    fun closeRootPicker() {
        _rootPicker.value = null
    }

    fun toggleRootPickerHidden() {
        rootPickerHidden = !rootPickerHidden
        val path = (_rootPicker.value as? DirectoryPickerUiState.Ready)?.path ?: _root.value
        loadRootDirs(path, rootPickerHidden)
    }

    fun pickupRootDir(path: String) {
        loadRootDirs(path, rootPickerHidden)
    }

    fun confirmRootPick() {
        val picked = (_rootPicker.value as? DirectoryPickerUiState.Ready)?.path ?: return
        _rootPicker.value = null
        selectRoot(picked)
    }

    private fun loadRootDirs(path: String?, showHidden: Boolean) {
        _rootPicker.value = DirectoryPickerUiState.Loading(path)
        viewModelScope.launch {
            when (val r = workspaceRepository.directories(path, showHidden)) {
                is NetworkResult.Success -> _rootPicker.value = DirectoryPickerUiState.Ready(
                    path = r.data.path,
                    parent = r.data.parent,
                    entries = r.data.entries,
                    showHidden = showHidden,
                    // 选浏览根不受「已是工作区」限制：同一个目录也可以直接看。
                    isWorkspace = false,
                )
                is NetworkResult.Error -> _rootPicker.value = DirectoryPickerUiState.Error(friendlyError(r))
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
        when (val r = repository.changes(workspaceId, _root.value)) {
            is NetworkResult.Success -> {
                _uiState.value = FilesUiState.Content(
                    isGitRepo = r.data.isGitRepo,
                    repos = r.data.repos,
                    files = r.data.files,
                )
            }
            is NetworkResult.Error -> _uiState.value = FilesUiState.Error(friendlyError(r))
        }
    }

    fun openDiff(file: ChangedFileDto) {
        if (file.isDirectory) return
        _diffState.value = DiffUiState.Loading(file.path)
        viewModelScope.launch {
            when (val r = repository.diff(WorkspaceSelection.current(), file.path, _root.value)) {
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
            root = _root.value,
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
            when (val r = repository.fileContent(WorkspaceSelection.current(), path, _root.value)) {
                is NetworkResult.Success -> _fileContent.value = FileContentUiState.Ready(r.data)
                is NetworkResult.Error -> _fileContent.value = FileContentUiState.Error(path, friendlyError(r))
            }
        }
    }

    fun closeFile() {
        _fileContent.value = null
    }

}
