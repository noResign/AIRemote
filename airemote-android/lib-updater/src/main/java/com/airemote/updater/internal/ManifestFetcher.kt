package com.airemote.updater.internal

import com.airemote.updater.UpdateException
import com.airemote.updater.UpdateManifest
import com.airemote.updater.UpdaterConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class ManifestFetcher(
    private val config: UpdaterConfig,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun fetch(): UpdateManifest = withContext(Dispatchers.IO) {
        if (!isAllowedUpdateUrl(config.manifestUrl)) {
            throw UpdateException.ManifestInvalid("manifestUrl must be HTTPS (or localhost for debug): ${config.manifestUrl}")
        }
        val request = Request.Builder()
            .url(config.manifestUrl)
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateException.Network("manifest request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw UpdateException.ManifestInvalid("empty manifest response")
            val manifest = json.decodeFromString<UpdateManifest>(body)
            validate(manifest)
            manifest
        }
    }

    private fun validate(manifest: UpdateManifest) {
        if (manifest.channel != config.channel) {
            throw UpdateException.ChannelMismatch(config.channel, manifest.channel)
        }
        if (manifest.versionCode <= 0) {
            throw UpdateException.ManifestInvalid("versionCode must be positive")
        }
        if (manifest.versionName.isBlank()) {
            throw UpdateException.ManifestInvalid("versionName is required")
        }
        if (manifest.apkSize <= 0) {
            throw UpdateException.ManifestInvalid("apkSize must be positive")
        }
        if (!isAllowedUpdateUrl(manifest.apkUrl)) {
            throw UpdateException.ManifestInvalid("apkUrl must be HTTPS: ${manifest.apkUrl}")
        }
        if (!manifest.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            throw UpdateException.ManifestInvalid("sha256 must be a 64-character hex string")
        }
    }
}

internal fun isAllowedUpdateUrl(raw: String): Boolean {
    val url = raw.toHttpUrlOrNull() ?: return false
    if (url.isHttps) return true
    return url.host == "localhost" ||
        url.host == "127.0.0.1" ||
        url.host == "10.0.2.2" ||
        url.host == "::1"
}
