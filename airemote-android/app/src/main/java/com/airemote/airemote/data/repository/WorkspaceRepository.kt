package com.airemote.airemote.data.repository

import com.airemote.airemote.data.local.SettingsStore
import com.airemote.network.airemote.AiremoteApi
import com.airemote.network.airemote.AiremoteClient
import com.airemote.network.airemote.dto.ConfigResponse
import com.airemote.network.airemote.dto.CreateWorkspaceRequest
import com.airemote.network.airemote.dto.DirectoriesResponse
import com.airemote.network.airemote.dto.UpdateConfigRequest
import com.airemote.network.airemote.dto.UpdateWorkspaceRequest
import com.airemote.network.airemote.dto.WorkspaceDto
import com.airemote.network.http.NetworkResult
import com.airemote.network.http.safeApiCall

class WorkspaceRepository(
    private val settings: SettingsStore = SettingsStore,
) {

    private fun api(): AiremoteApi? {
        val url = settings.baseUrl ?: return null
        val token = settings.token ?: return null
        return AiremoteClient.create(url, token)
    }

    suspend fun listWorkspaces(): NetworkResult<List<WorkspaceDto>> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.workspaces() }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.workspaces)
            is NetworkResult.Error -> r
        }
    }

    suspend fun config(): NetworkResult<ConfigResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.config() }
    }

    suspend fun updateConfig(request: UpdateConfigRequest): NetworkResult<ConfigResponse?> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.updateConfig(request) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.config)
            is NetworkResult.Error -> r
        }
    }

    suspend fun directories(path: String? = null, showHidden: Boolean? = null): NetworkResult<DirectoriesResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.directories(path, showHidden) }
    }

    suspend fun createWorkspace(path: String, name: String? = null): NetworkResult<WorkspaceDto> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.createWorkspace(CreateWorkspaceRequest(name = name, path = path)) }) {
            is NetworkResult.Success -> r.data.workspace?.let { NetworkResult.Success(it) }
                ?: NetworkResult.Error(-2, "daemon 未返回 workspace")
            is NetworkResult.Error -> r
        }
    }

    suspend fun setDefault(workspaceId: String): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.updateWorkspace(workspaceId, UpdateWorkspaceRequest(isDefault = true)) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }

    suspend fun updateWorkspace(
        workspaceId: String,
        name: String? = null,
        enabled: Boolean? = null,
    ): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall {
            api.updateWorkspace(workspaceId, UpdateWorkspaceRequest(name = name, enabled = enabled))
        }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }

    suspend fun deleteWorkspace(workspaceId: String): NetworkResult<Unit> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.deleteWorkspace(workspaceId) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> r
        }
    }
}
