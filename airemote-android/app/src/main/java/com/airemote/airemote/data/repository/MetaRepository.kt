package com.airemote.airemote.data.repository

import com.airemote.network.airemote.AiremoteApi
import com.airemote.network.airemote.AiremoteClient
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.network.airemote.dto.AgentsResponse
import com.airemote.network.airemote.dto.ClaudeSessionsResponse
import com.airemote.network.airemote.dto.HealthResponse
import com.airemote.network.http.NetworkResult
import com.airemote.network.http.safeApiCall

/** Daemon 元数据（agents / 本机 Claude 会话 / 版本）。 */
class MetaRepository(
    private val settings: SettingsStore = SettingsStore,
) {

    private fun api(): AiremoteApi? {
        val url = settings.baseUrl ?: return null
        val token = settings.token ?: return null
        return AiremoteClient.create(url, token)
    }

    suspend fun agents(): NetworkResult<AgentsResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.agents() }
    }

    suspend fun claudeSessions(workspaceId: String? = null): NetworkResult<ClaudeSessionsResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.claudeSessions(workspaceId) }
    }

    suspend fun health(): NetworkResult<HealthResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.health() }
    }
}
