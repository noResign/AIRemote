package com.airemote.airemote.data

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Builds a Retrofit [AiremoteApi] against a user-supplied base URL + token. The
 * daemon URL is not known at compile time, so a fresh Retrofit instance is
 * created per connection target while the underlying OkHttp client is shared.
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

    /** Build an API instance that injects `Authorization: Bearer <token>` on every call. */
    fun create(baseUrl: String, token: String): AiremoteApi {
        val client = baseOkHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AiremoteApi::class.java)
    }
}
