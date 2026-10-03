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
import okhttp3.OkHttpClient

class ChangesRepository(
    private val settings: SettingsStore = SettingsStore,
) {

    private fun api(): AiremoteApi? {
        val url = settings.baseUrl ?: return null
        val token = settings.token ?: return null
        return AiremoteClient.create(url, token)
    }

    suspend fun changes(workspaceId: String?, root: String? = null): NetworkResult<ChangesResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.changes(workspaceId, root) }
    }

    suspend fun diff(
        workspaceId: String?,
        path: String,
        root: String? = null,
    ): NetworkResult<DiffResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.changeDiff(workspaceId, root, path) }
    }

    suspend fun files(
        workspaceId: String?,
        root: String? = null,
        path: String? = null,
        cursor: String? = null,
        limit: Int? = null,
        showHidden: Boolean = false,
        showIgnored: Boolean = false,
    ): NetworkResult<FilesResponse> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.files(workspaceId, root, path, cursor, limit, showHidden, showIgnored) }
    }

    suspend fun fileContent(
        workspaceId: String?,
        path: String,
        root: String? = null,
    ): NetworkResult<FileContentDto> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return safeApiCall { api.fileContent(workspaceId, root, path) }
    }

    /**
     * 原始字节流地址（`GET /api/files/raw`），交给 Coil / ExoPlayer 自己去取。
     * 未配置连接时返回 null。
     */
    fun mediaUrl(workspaceId: String?, path: String, root: String? = null): String? {
        val baseUrl = settings.baseUrl ?: return null
        if (settings.token.isNullOrBlank()) return null
        return AiremoteClient.fileRawUrl(baseUrl, workspaceId, root, path)
    }

    /**
     * 带鉴权的 OkHttp 客户端，供 Coil / ExoPlayer 复用——token 只进请求头、不进 URL。
     * 未配置连接时返回 null。
     */
    fun mediaClient(): OkHttpClient? {
        val baseUrl = settings.baseUrl ?: return null
        val token = settings.token ?: return null
        return AiremoteClient.authedHttpClient(baseUrl, token)
    }
}
