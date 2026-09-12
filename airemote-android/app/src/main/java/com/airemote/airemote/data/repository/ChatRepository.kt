package com.airemote.airemote.data.repository

import com.airemote.network.airemote.AiremoteClient
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.network.airemote.AiremoteStream
import com.airemote.network.airemote.ChatStreamEvent
import com.airemote.network.airemote.dto.ChatRequest
import com.airemote.network.airemote.dto.EventsResponse
import com.airemote.network.airemote.dto.PermissionDecisionRequest
import com.airemote.network.http.NetworkResult
import com.airemote.network.http.safeApiCall
import com.airemote.network.airemote.dto.SessionDetailResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ChatRepository(
    private val settings: SettingsStore = SettingsStore,
    private val stream: AiremoteStream = AiremoteStream.default,
) {

    private fun api() = settings.baseUrl?.let { url ->
        settings.token?.let { token -> AiremoteClient.create(url, token) }
    }

    fun chatStream(
        sessionId: String?,
        prompt: String,
        claudeSessionId: String? = null,
        runtime: String? = null,
        workspaceId: String? = null,
        permissionMode: String? = null,
    ): Flow<ChatStreamEvent> {
        val url = settings.baseUrl ?: return flowOf(ChatStreamEvent.Failed("未配置连接"))
        val token = settings.token ?: return flowOf(ChatStreamEvent.Failed("未配置连接"))
        return stream.chat(
            url,
            token,
            ChatRequest(
                prompt = prompt,
                sessionId = sessionId,
                workspaceId = workspaceId,
                claudeSessionId = claudeSessionId,
                runtime = runtime,
                permissionMode = permissionMode,
            ),
        )
    }

    fun runStream(runId: String, after: Long? = null): Flow<ChatStreamEvent> {
        val url = settings.baseUrl ?: return flowOf(ChatStreamEvent.Failed("未配置连接"))
        val token = settings.token ?: return flowOf(ChatStreamEvent.Failed("未配置连接"))
        return stream.streamRun(url, token, runId, after)
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
