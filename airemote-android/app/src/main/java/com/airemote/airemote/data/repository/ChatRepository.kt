package com.airemote.airemote.data.repository

import com.airemote.airemote.data.AiremoteClient
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.airemote.data.sse.AiremoteSse
import com.airemote.airemote.data.sse.ChatStreamEvent
import com.airemote.airemote.model.event.ChatRequest
import com.airemote.airemote.model.event.EventsResponse
import com.airemote.airemote.model.event.PermissionDecisionRequest
import com.airemote.airemote.model.network.NetworkResult
import com.airemote.airemote.model.network.safeApiCall
import com.airemote.airemote.model.session.SessionDetailResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ChatRepository(
    private val settings: SettingsStore = SettingsStore,
) {

    private fun api() = settings.baseUrl?.let { url ->
        settings.token?.let { token -> AiremoteClient.create(url, token) }
    }

    fun chatStream(
        sessionId: String?,
        prompt: String,
        cwd: String? = null,
        claudeSessionId: String? = null,
        runtime: String? = null,
    ): Flow<ChatStreamEvent> {
        val url = settings.baseUrl ?: return flowOf(ChatStreamEvent.Failed("未配置连接"))
        val token = settings.token ?: return flowOf(ChatStreamEvent.Failed("未配置连接"))
        return AiremoteSse.chat(
            url,
            token,
            ChatRequest(
                prompt = prompt,
                sessionId = sessionId,
                cwd = cwd,
                claudeSessionId = claudeSessionId,
                runtime = runtime,
            ),
        )
    }

    fun runStream(runId: String, after: Long? = null): Flow<ChatStreamEvent> {
        val url = settings.baseUrl ?: return flowOf(ChatStreamEvent.Failed("未配置连接"))
        val token = settings.token ?: return flowOf(ChatStreamEvent.Failed("未配置连接"))
        return AiremoteSse.streamRun(url, token, runId, after)
    }

    suspend fun sessionDetail(id: String): NetworkResult<SessionDetailResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.session(id) }
    }

    suspend fun runEvents(runId: String): NetworkResult<EventsResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.runEvents(runId) }
    }

    suspend fun decidePermission(permissionId: String, decision: String, reason: String? = null): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.decidePermission(permissionId, PermissionDecisionRequest(decision, reason)) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }

    suspend fun cancelRun(runId: String): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.cancelRun(runId) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }
}
