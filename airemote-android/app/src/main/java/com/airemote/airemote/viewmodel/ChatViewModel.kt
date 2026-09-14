package com.airemote.airemote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.PendingNewSession
import com.airemote.airemote.data.repository.ChatRepository
import com.airemote.airemote.data.repository.SessionRepository
import com.airemote.airemote.util.friendlyError
import com.airemote.network.airemote.ChatStreamEvent
import com.airemote.airemote.model.chat.ChatUiMessage
import com.airemote.airemote.model.chat.ContentBlock
import com.airemote.airemote.model.chat.TodoItem
import com.airemote.airemote.model.chat.UsageInfo
import com.airemote.airemote.model.chat.parseTodos
import com.airemote.network.airemote.dto.NormalizedEvent
import com.airemote.network.airemote.dto.PermissionGrantDto
import com.airemote.network.airemote.dto.SseFrame
import com.airemote.network.airemote.dto.MessageDto
import com.airemote.network.airemote.dto.RunDto
import com.airemote.network.http.NetworkResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed class SessionPermissionsUiState {
    object Loading : SessionPermissionsUiState()
    data class Ready(
        val mode: String,
        val grants: List<PermissionGrantDto>,
    ) : SessionPermissionsUiState()
    data class Error(val message: String) : SessionPermissionsUiState()
}

class ChatViewModel(
    private val repository: ChatRepository = ChatRepository(),
    private val sessionRepository: SessionRepository = SessionRepository(),
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _todos = MutableStateFlow<List<TodoItem>>(emptyList())
    val todos = _todos.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming = _streaming.asStateFlow()

    private val _runId = MutableStateFlow<String?>(null)
    val runId = _runId.asStateFlow()

    private val _sessionTitle = MutableStateFlow<String?>(null)
    val sessionTitle = _sessionTitle.asStateFlow()

    private val _sessionCwd = MutableStateFlow<String?>(null)
    val sessionCwd = _sessionCwd.asStateFlow()

    private val _sessionRuntime = MutableStateFlow<String?>(null)
    val sessionRuntime = _sessionRuntime.asStateFlow()

    private val _permission = MutableStateFlow<NormalizedEvent.PermissionRequest?>(null)
    val permission = _permission.asStateFlow()

    // 并发工具调用会同时推多个 permission_request，排队逐个弹窗，避免后到的覆盖先到的
    private val permissionQueue = ArrayDeque<NormalizedEvent.PermissionRequest>()
    private val queuedPermissionIds = mutableSetOf<String>()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _sessionPermissions = MutableStateFlow<SessionPermissionsUiState?>(null)
    val sessionPermissions = _sessionPermissions.asStateFlow()

    private var sessionId: String? = null
    private var streamJob: Job? = null

    // 新建会话时由 Sheet 传入的续接 Claude 会话 / runtime / Workspace / 权限模式
    private var initialClaudeSessionId: String? = null
    private var initialRuntime: String? = null
    private var initialWorkspaceId: String? = null
    private var initialPermissionMode: String? = null

    fun load(sessionId: String?) {
        this.sessionId = sessionId
        if (sessionId == null) {
            PendingNewSession.take()?.let {
                initialClaudeSessionId = it.claudeSessionId
                initialRuntime = it.runtime
                initialWorkspaceId = it.workspaceId
                initialPermissionMode = it.permissionMode
            }
            return
        }
        viewModelScope.launch {
            when (val r = repository.sessionDetail(sessionId)) {
                is NetworkResult.Success -> {
                    val session = r.data.session
                    _sessionTitle.value = session.title
                    _sessionCwd.value = session.cwd
                    _sessionRuntime.value = session.runtime
                    // runningRunId 来自另一个 module，无法直接智能转换，先取到局部变量
                    val runningRunId = session.runningRunId
                    _messages.value = reconstructHistory(r.data.runs, runningRunId, r.data.messages)
                    if (session.running && runningRunId != null) {
                        attach(runningRunId)
                    }
                }
                is NetworkResult.Error -> _error.value = friendlyError(r)
            }
        }
    }

    /**
     * 用「run + 逐 run 回放 events」重建完整历史（含思考块 / 工具卡 / 用量），
     * 而不是只看聚合后的 messages。正在运行的 run 留空、交给 attach() 续流。
     */
    private suspend fun reconstructHistory(
        runs: List<RunDto>,
        runningRunId: String?,
        messages: List<MessageDto>,
    ): List<ChatUiMessage> {
        val list = mutableListOf<ChatUiMessage>()
        for (run in runs) {
            list += ChatUiMessage.User(run.prompt)
            if (run.id == runningRunId) {
                // 运行中：attach() 会追加并续流
                list += ChatUiMessage.Assistant()
            } else {
                when (val ev = repository.runEvents(run.id)) {
                    is NetworkResult.Success -> list += buildAssistantFromEvents(ev.data.events)
                    is NetworkResult.Error -> list += ChatUiMessage.Assistant(done = true)
                }
            }
        }
        // 兜底：没有任何 run（历史遗留数据）时，退回聚合 messages
        if (list.isEmpty() && messages.isNotEmpty()) {
            list += messages.map { m ->
                if (m.role == "user") ChatUiMessage.User(m.content)
                else ChatUiMessage.Assistant(blocks = listOf(ContentBlock.Text(m.content)), done = true)
            }
        }
        return list
    }

    private fun buildAssistantFromEvents(events: List<SseFrame>): ChatUiMessage.Assistant {
        var a = ChatUiMessage.Assistant()
        for (frame in events) {
            val e = frame.event
            if (e is NormalizedEvent.PermissionRequest) continue // 审批请求是瞬态，不进历史
            if (e is NormalizedEvent.ToolUse && e.name == "TodoWrite") {
                _todos.value = parseTodos(e.input)
                continue
            }
            a = updateAssistant(a, e)
        }
        return if (a.done) a else a.copy(done = true)
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _streaming.value) return
        if (sessionId == null && _sessionTitle.value == null) {
            _sessionTitle.value = prompt.take(60)
        }
        _messages.update { it + ChatUiMessage.User(prompt) + ChatUiMessage.Assistant() }
        _error.value = null
        start(
            repository.chatStream(
                sessionId = sessionId,
                prompt = prompt,
                claudeSessionId = initialClaudeSessionId,
                runtime = initialRuntime,
                workspaceId = initialWorkspaceId,
                permissionMode = initialPermissionMode,
            )
        )
        initialClaudeSessionId = null
        initialRuntime = null
        initialWorkspaceId = null
        initialPermissionMode = null
    }

    private fun attach(runId: String) {
        if (_streaming.value) return
        _messages.update { list ->
            val last = list.lastOrNull()
            if (last is ChatUiMessage.Assistant && !last.done) list else list + ChatUiMessage.Assistant()
        }
        start(repository.runStream(runId, after = null))
    }

    private fun start(flow: Flow<ChatStreamEvent>) {
        streamJob?.cancel()
        _streaming.value = true
        streamJob = viewModelScope.launch {
            flow.collect { evt ->
                when (evt) {
                    is ChatStreamEvent.Frame -> handleFrame(evt)
                    is ChatStreamEvent.Failed -> {
                        _error.value = evt.message
                        // 传输失败不代表 daemon 上的 run 结束；保留待审批弹窗，
                        // 网络恢复后用户仍可作出决定。
                        finish(clearPermissions = false)
                    }
                    is ChatStreamEvent.Closed -> finish()
                }
            }
        }
    }

    private fun handleFrame(evt: ChatStreamEvent.Frame) {
        val frame = evt.frame
        _runId.value = frame.runId
        val e = frame.event
        if (e is NormalizedEvent.Status && e.sessionId != null && sessionId == null) {
            sessionId = e.sessionId
        }
        when (e) {
            is NormalizedEvent.PermissionRequest -> enqueuePermission(e)
            else -> applyEvent(e)
        }
        if (isTerminal(e)) finish()
    }

    private fun isTerminal(e: NormalizedEvent): Boolean = when (e) {
        is NormalizedEvent.Status -> e.terminal == true
        is NormalizedEvent.Error -> e.terminal == true
        else -> false
    }

    fun openSessionPermissions() {
        val id = sessionId ?: return
        _sessionPermissions.value = SessionPermissionsUiState.Loading
        viewModelScope.launch {
            when (val r = sessionRepository.permissions(id)) {
                is NetworkResult.Success -> _sessionPermissions.value = SessionPermissionsUiState.Ready(
                    mode = r.data.mode,
                    grants = r.data.grants,
                )
                is NetworkResult.Error -> _sessionPermissions.value = SessionPermissionsUiState.Error(friendlyError(r))
            }
        }
    }

    fun closeSessionPermissions() {
        _sessionPermissions.value = null
    }

    fun updateSessionPermissionMode(mode: String) {
        val id = sessionId ?: return
        viewModelScope.launch {
            when (val r = sessionRepository.updatePermissionMode(id, mode)) {
                is NetworkResult.Success -> openSessionPermissions()
                is NetworkResult.Error -> _error.value = friendlyError(r)
            }
        }
    }

    fun revokePermissionGrant(toolName: String) {
        val id = sessionId ?: return
        viewModelScope.launch {
            when (val r = sessionRepository.deletePermissionGrant(id, toolName)) {
                is NetworkResult.Success -> openSessionPermissions()
                is NetworkResult.Error -> _error.value = friendlyError(r)
            }
        }
    }

    fun revokeAllPermissionGrants() {
        val id = sessionId ?: return
        viewModelScope.launch {
            when (val r = sessionRepository.deletePermissionGrants(id)) {
                is NetworkResult.Success -> openSessionPermissions()
                is NetworkResult.Error -> _error.value = friendlyError(r)
            }
        }
    }

    private fun finish(clearPermissions: Boolean = true) {
        _streaming.value = false
        _runId.value = null
        if (clearPermissions) {
            _permission.value = null
            permissionQueue.clear()
            queuedPermissionIds.clear()
        }
        _messages.update { list ->
            val lastIndex = list.lastIndex
            list.mapIndexed { i, m ->
                if (i == lastIndex && m is ChatUiMessage.Assistant) m.copy(done = true) else m
            }
        }
    }

    private fun applyEvent(e: NormalizedEvent) {
        if (e is NormalizedEvent.ToolUse && e.name == "TodoWrite") {
            _todos.value = parseTodos(e.input)
            return
        }
        _messages.update { list ->
            val idx = list.indexOfLast { it is ChatUiMessage.Assistant }
            if (idx < 0) {
                list + ChatUiMessage.Assistant()
            } else {
                val mutable = list.toMutableList()
                mutable[idx] = updateAssistant(mutable[idx] as ChatUiMessage.Assistant, e)
                mutable
            }
        }
    }

    private fun updateAssistant(a: ChatUiMessage.Assistant, e: NormalizedEvent): ChatUiMessage.Assistant = when (e) {
        is NormalizedEvent.Status -> if (e.terminal == true) a.copy(done = true) else a
        is NormalizedEvent.TextDelta -> a.copy(blocks = appendText(a.blocks, e.delta))
        is NormalizedEvent.ThinkingDelta -> a.copy(blocks = appendThinking(a.blocks, e.delta))
        is NormalizedEvent.ThinkingStart -> a
        is NormalizedEvent.ToolUse -> a.copy(
            blocks = a.blocks + ContentBlock.ToolUse(id = e.id, name = e.name, input = e.input, running = true)
        )
        is NormalizedEvent.ToolResult -> a.copy(
            blocks = a.blocks.map { b ->
                if (b is ContentBlock.ToolUse && b.id == e.toolUseId) {
                    b.copy(result = e.content, isError = e.isError == true, running = false)
                } else {
                    b
                }
            }
        )
        is NormalizedEvent.Usage -> a.copy(usage = parseUsage(e))
        is NormalizedEvent.TurnEnd -> a
        is NormalizedEvent.Error -> a.copy(error = e.message, done = e.terminal == true)
        is NormalizedEvent.PermissionRequest -> a
        is NormalizedEvent.Question -> a.copy(
            blocks = a.blocks + ContentBlock.Question(toolUseId = e.toolUseId, questions = e.questions)
        )
    }

    private fun appendText(blocks: List<ContentBlock>, delta: String): List<ContentBlock> {
        val last = blocks.lastOrNull()
        return if (last is ContentBlock.Text) {
            blocks.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            blocks + ContentBlock.Text(delta)
        }
    }

    private fun appendThinking(blocks: List<ContentBlock>, delta: String): List<ContentBlock> {
        val last = blocks.lastOrNull()
        return if (last is ContentBlock.Thinking) {
            blocks.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            blocks + ContentBlock.Thinking(delta)
        }
    }

    private fun parseUsage(u: NormalizedEvent.Usage): UsageInfo? {
        val obj = u.usage as? JsonObject
        val input = (obj?.get("input_tokens") as? JsonPrimitive)?.content?.toLongOrNull()
        val output = (obj?.get("output_tokens") as? JsonPrimitive)?.content?.toLongOrNull()
        if (input == null && output == null && u.costUsd == null) return null
        return UsageInfo(inputTokens = input, outputTokens = output, costUsd = u.costUsd)
    }

    fun answerQuestion(toolUseId: String, answerText: String) {
        if (_streaming.value || answerText.isBlank()) return
        _messages.update { list ->
            list.map { m ->
                if (m is ChatUiMessage.Assistant) {
                    m.copy(blocks = m.blocks.map { b ->
                        if (b is ContentBlock.Question && b.toolUseId == toolUseId) b.copy(answered = true)
                        else b
                    })
                } else m
            }
        }
        send(answerText)
    }

    fun decidePermission(decision: String, reason: String? = null) {
        val p = _permission.value ?: return
        queuedPermissionIds.remove(p.permissionId)
        _permission.value = permissionQueue.removeFirstOrNull()
        viewModelScope.launch {
            val r = repository.decidePermission(p.permissionId, decision, reason)
            if (r is NetworkResult.Error) _error.value = friendlyError(r)
        }
    }

    private fun enqueuePermission(req: NormalizedEvent.PermissionRequest) {
        // 已决（allowed/denied/timed_out）状态帧表示该请求不需要再审批：
        // 重连回放时跳过，运行中收到状态更新时还要把已排队的同 id 请求移除。
        if (req.status != null && req.status != "pending") {
            dismissPermission(req.permissionId)
            return
        }
        if (!queuedPermissionIds.add(req.permissionId)) return
        if (_permission.value == null) {
            _permission.value = req
        } else {
            permissionQueue.addLast(req)
        }
    }

    private fun dismissPermission(permissionId: String) {
        queuedPermissionIds.remove(permissionId)
        val remaining = permissionQueue.filterNot { it.permissionId == permissionId }
        permissionQueue.clear()
        permissionQueue.addAll(remaining)
        if (_permission.value?.permissionId == permissionId) {
            _permission.value = permissionQueue.removeFirstOrNull()
        }
    }

    fun stop() {
        val rid = _runId.value
        streamJob?.cancel()
        finish()
        if (rid != null) {
            viewModelScope.launch { repository.cancelRun(rid) }
        }
    }

    fun consumeError() {
        _error.value = null
    }
}
