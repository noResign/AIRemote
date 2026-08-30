package com.airemote.airemote.data.repository

import com.airemote.airemote.data.AiremoteApi
import com.airemote.airemote.data.AiremoteClient
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.airemote.model.agent.AgentsResponse
import com.airemote.airemote.model.claude.ClaudeSessionsResponse
import com.airemote.airemote.model.connect.HealthResponse
import com.airemote.airemote.model.network.NetworkResult
import com.airemote.airemote.model.network.safeApiCall
import com.airemote.airemote.model.workspace.WorkspacesResponse

/** Daemon 元数据（工作目录 / agents / 本机 Claude 会话 / 版本）。 */
class MetaRepository(
    private val settings: SettingsStore = SettingsStore,
) {

    private fun api(): AiremoteApi? {
        val url = settings.baseUrl ?: return null
        val token = settings.token ?: return null
        return AiremoteClient.create(url, token)
    }

    suspend fun workspaces(): NetworkResult<WorkspacesResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.workspaces() }
    }

    suspend fun agents(): NetworkResult<AgentsResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.agents() }
    }

    suspend fun claudeSessions(): NetworkResult<ClaudeSessionsResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.claudeSessions() }
    }

    suspend fun health(): NetworkResult<HealthResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.health() }
    }
}
