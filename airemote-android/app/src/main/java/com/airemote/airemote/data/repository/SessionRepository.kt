package com.airemote.airemote.data.repository

import com.airemote.network.airemote.AiremoteApi
import com.airemote.network.airemote.AiremoteClient
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.network.http.NetworkResult
import com.airemote.network.http.safeApiCall
import com.airemote.network.airemote.dto.SessionDto
import com.airemote.network.airemote.dto.SessionPermissionsResponse
import com.airemote.network.airemote.dto.UpdateSessionPermissionsRequest

class SessionRepository(
    private val settings: SettingsStore = SettingsStore,
) {

    private fun api(): AiremoteApi? {
        val url = settings.baseUrl ?: return null
        val token = settings.token ?: return null
        return AiremoteClient.create(url, token)
    }

    suspend fun listSessions(workspaceId: String? = null): NetworkResult<List<SessionDto>> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.sessions(workspaceId) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.sessions)
            is NetworkResult.Error -> r
        }
    }

    suspend fun deleteSession(id: String): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.deleteSession(id) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }

    suspend fun permissions(id: String): NetworkResult<SessionPermissionsResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.sessionPermissions(id) }
    }

    suspend fun updatePermissionMode(id: String, mode: String): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.updateSessionPermissions(id, UpdateSessionPermissionsRequest(mode)) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }

    suspend fun deletePermissionGrant(id: String, toolName: String): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.deletePermissionGrant(id, toolName) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }

    suspend fun deletePermissionGrants(id: String): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.deletePermissionGrants(id) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }
}
