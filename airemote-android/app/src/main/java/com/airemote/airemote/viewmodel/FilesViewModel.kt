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

    /** 工作区的根：主目录 + 它的附加目录（后者对 agent 是授权目录）。 */
    private val _knownRoots = MutableStateFlow<List<String>>(emptyList())
    val knownRoots = _knownRoots.asStateFlow()

    /**
     * 浏览书签。和 [knownRoots] 一起构成 tab 行，但**不授予 agent 权限**，
     * 只是「我想在这里留个入口」；由用户增删，存后端（`workspace_shortcut_dirs`）。
     */
    private val _shortcuts = MutableStateFlow<List<String>>(emptyList())
    val shortcuts = _shortcuts.asStateFlow()

    /** 选根对话框：走绝对路径浏览（`/api/fs/directories`），因此能到工作区之外。 */
    private val _rootPicker = MutableStateFlow<DirectoryPickerUiState?>(null)
    val rootPicker = _rootPicker.asStateFlow()
    private var rootPickerHidden = false

    /** 对话框确认后是「切过去看看」还是「加为浏览书签」；由打开它的入口决定，UI 据此换文案。 */
    private val _addingShortcut = MutableStateFlow(false)
    val addingShortcut = _addingShortcut.asStateFlow()

    /** 加书签失败（比如那个目录已经在 tab 行上了）时的提示。 */
    private val _rootError = MutableStateFlow<String?>(null)
    val rootError = _rootError.asStateFlow()

    /** 下拉指示器。只由下拉刷新驱动——进入 tab 的自动刷新不显示它，否则每次切页都闪一下。 */
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

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
                _rootError.value = null
                _knownRoots.value = emptyList()
                _shortcuts.value = emptyList()
                loadChanges(workspaceId)
                if (_mode.value == FilesMode.All) browse(null)
            }
        }
    }

    /**
     * 两组 chip 都由每次列表响应回显（`roots` + `shortcutDirs`），不再单独拉工作区列表——
     * 这样附加目录无论从哪加的（工作区管理页、聊天里的越界审批）、书签从哪加的，都会自动反映。
     */
    private fun syncChips(roots: List<String>, shortcuts: List<String>) {
        if (roots.isNotEmpty()) _knownRoots.value = roots
        _shortcuts.value = shortcuts
    }

    /**
     * 切根：改动列表与文件路径都是相对根的，所以一并重置。
     * [force] 用于「根没变但数据需要重载」的场景（比如刚把一个已是当前根的目录加成附加目录）。
     */
    fun selectRoot(root: String?, force: Boolean = false) {
        val normalized = root?.takeIf { it.isNotBlank() }
        if (!force && _root.value == normalized) return
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

    /** 「切换」：选一个目录只是切过去看，不动任何配置。 */
    fun openRootPicker() {
        rootPickerHidden = false
        _addingShortcut.value = false
        loadRootDirs(_root.value, false)
    }

    /**
     * 「＋ 目录」：把选中的目录加成一个**浏览书签**（tab）。它不写授权目录，
     * 所以 agent 不会因此获得任何权限——想授权请走工作区管理页的「+ 附加目录」
     * 或聊天里的越界读取审批。
     */
    fun openAddShortcutPicker() {
        rootPickerHidden = false
        _addingShortcut.value = true
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
        if (_addingShortcut.value) addShortcut(picked) else selectRoot(picked)
    }

    private fun addShortcut(dir: String) {
        val workspaceId = WorkspaceSelection.current() ?: return
        _rootError.value = null
        viewModelScope.launch {
            when (val r = workspaceRepository.addShortcut(workspaceId, dir)) {
                is NetworkResult.Success -> {
                    _shortcuts.value = r.data
                    // 加完直接切过去看它。force 绕过「根没变就返回」，以便重载数据。
                    selectRoot(dir, force = true)
                }
                // 409 shortcut_exists 等都要说清楚，否则用户不知道为何没加上。
                is NetworkResult.Error -> _rootError.value = friendlyError(r)
            }
        }
    }

    /** 移除一个浏览书签。若正看着它，退回工作区主目录，避免停在一个没有 tab 的目录上。 */
    fun removeShortcut(dir: String) {
        val workspaceId = WorkspaceSelection.current() ?: return
        viewModelScope.launch {
            when (val r = workspaceRepository.removeShortcut(workspaceId, dir)) {
                is NetworkResult.Success -> {
                    _shortcuts.value = r.data
                    if (_root.value == dir) selectRoot(null)
                }
                is NetworkResult.Error -> _rootError.value = friendlyError(r)
            }
        }
    }

    fun dismissRootError() {
        _rootError.value = null
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

    /** 正在看 diff / 文件内容时页面显示的不是列表，刷新没有意义。 */
    private fun listIsBehindOverlay(): Boolean = _fileContent.value != null || _diffState.value != null

    /**
     * 重新拉取当前分段的数据。页面每次进入 tab 都会调它（见 `FilesScreen` 的
     * `LaunchedEffect`），所以 agent 在后台改了文件、或者别处动了 tab 行，切回来就能看到。
     * 单层分页（≤200 条/次）成本很低。
     */
    fun refresh() {
        if (listIsBehindOverlay()) return
        viewModelScope.launch { reload() }
    }

    /** 下拉刷新：与自动刷新走同一套加载，只是额外驱动下拉指示器。 */
    fun pullRefresh() {
        if (listIsBehindOverlay()) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                reload()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun reload() {
        when (_mode.value) {
            FilesMode.Changes -> {
                _uiState.value = FilesUiState.Loading
                loadChanges(WorkspaceSelection.current())
            }
            FilesMode.All -> browseAndWait(_fileBrowser.value.path.ifBlank { null })
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
                syncChips(r.data.roots, r.data.shortcutDirs)
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
        viewModelScope.launch { browseAndWait(path) }
    }

    /** [browse] 的可等待版本，供下拉/自动刷新在加载结束后收起指示器。 */
    private suspend fun browseAndWait(path: String?) {
        _fileBrowser.value = _fileBrowser.value.copy(loading = true, error = null)
        loadFiles(WorkspaceSelection.current(), path, cursor = null, append = false)
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
                syncChips(r.data.roots, r.data.shortcutDirs)
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
