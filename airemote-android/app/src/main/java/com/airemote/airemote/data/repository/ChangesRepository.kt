package com.airemote.airemote.data.repository

import com.airemote.airemote.data.local.SettingsStore
import com.airemote.network.airemote.AiremoteApi
import com.airemote.network.airemote.AiremoteClient
import com.airemote.network.airemote.dto.ChangesResponse
import com.airemote.network.airemote.dto.DiffResponse
import com.airemote.network.airemote.dto.FileContentDto
import com.airemote.network.airemote.dto.FilesResponse
import com.airemote.network.http.NetworkResult
import com.airemote.network.http.safeApiCall

class ChangesRepository(
    private val settings: SettingsStore = SettingsStore,
) {

    private fun api(): AiremoteApi? {
        val url = settings.baseUrl ?: return null
        val token = settings.token ?: return null
        return AiremoteClient.create(url, token)
    }

    suspend fun changes(workspaceId: String?, dir: String = ""): NetworkResult<ChangesResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.changes(workspaceId, dir) }
    }

    suspend fun diff(workspaceId: String?, path: String, dir: String = ""): NetworkResult<DiffResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.changeDiff(workspaceId, path, dir) }
    }

    suspend fun files(
        workspaceId: String?,
        path: String? = null,
        cursor: String? = null,
        limit: Int? = null,
        showHidden: Boolean = false,
        showIgnored: Boolean = false,
    ): NetworkResult<FilesResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.files(workspaceId, path, cursor, limit, showHidden, showIgnored) }
    }

    suspend fun fileContent(workspaceId: String?, path: String): NetworkResult<FileContentDto> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.fileContent(workspaceId, path) }
    }
}
