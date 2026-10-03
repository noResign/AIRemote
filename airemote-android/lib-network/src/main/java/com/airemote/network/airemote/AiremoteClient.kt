package com.airemote.network.airemote

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds a Retrofit [AiremoteApi] against a user-supplied base URL + token. The
 * daemon URL is not known at compile time, so instances are built lazily and
 * cached per (baseUrl, token) while the underlying OkHttp client is shared.
 */
object AiremoteClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val baseOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Normalizes user input like `192.168.1.5:4780` into `http://192.168.1.5:4780/`. */
    fun normalizeBaseUrl(input: String): String {
        var url = input.trim()
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) url += "/"
        return url
    }

    private val apiCache = mutableMapOf<String, AiremoteApi>()
    private val clientCache = mutableMapOf<String, OkHttpClient>()

    /**
     * An OkHttp client that injects `Authorization: Bearer <token>` on every call,
     * shared per (baseUrl, token). Exposed for consumers that fetch bytes over
     * their own HTTP stack — Coil for images, ExoPlayer for video — so the token
     * stays in a header and never ends up in a URL.
     */
    @Synchronized
    fun authedHttpClient(baseUrl: String, token: String): OkHttpClient {
        val normalized = normalizeBaseUrl(baseUrl)
        val key = "$normalized|$token"
        return clientCache.getOrPut(key) {
            baseOkHttpClient.newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(request)
                }
                .build()
        }
    }

    /**
     * Absolute URL of a raw file read (`GET /api/files/raw`), for handing to Coil
     * or a media player. The caller's client must add the Authorization header —
     * this only shapes the URL (endpoint + encoded query).
     */
    fun fileRawUrl(baseUrl: String, workspaceId: String?, root: String?, path: String): String {
        val query = listOfNotNull(
            workspaceId?.let { "workspaceId=${encode(it)}" },
            root?.let { "root=${encode(it)}" },
            "path=${encode(path)}",
        ).joinToString("&")
        return normalizeBaseUrl(baseUrl) + "api/files/raw?$query"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    /** Build (or reuse) an API instance that injects `Authorization: Bearer <token>` on every call. */
    @Synchronized
    fun create(baseUrl: String, token: String): AiremoteApi {
        val normalized = normalizeBaseUrl(baseUrl)
        val key = "$normalized|$token"
        return apiCache.getOrPut(key) {
            Retrofit.Builder()
                .baseUrl(normalized)
                .client(authedHttpClient(normalized, token))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(AiremoteApi::class.java)
        }
    }
}
