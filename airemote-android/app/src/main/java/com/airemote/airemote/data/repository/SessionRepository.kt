package com.airemote.airemote.data.repository

import com.airemote.airemote.data.AiremoteApi
import com.airemote.airemote.data.AiremoteClient
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.airemote.model.network.NetworkResult
import com.airemote.airemote.model.network.safeApiCall
import com.airemote.airemote.model.session.SessionDto

class SessionRepository(
    private val settings: SettingsStore = SettingsStore,
) {

    private fun api(): AiremoteApi? {
        val url = settings.baseUrl ?: return null
        val token = settings.token ?: return null
        return AiremoteClient.create(url, token)
    }

    suspend fun listSessions(): NetworkResult<List<SessionDto>> {
        val api = api() ?: return NetworkResult.Error(-2, "未配置连接")
        return when (val r = safeApiCall { api.sessions() }) {
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
}
