package com.airemote.network.http

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * 错误响应里可选的机器可读字段。协议无关：任何返回 `{error?, code?}` 的后端都能用，
 * 具体取值由调用方（业务层）解释，故不依赖 `airemote/` 协议层。
 */
@Serializable
internal data class ApiErrorBody(
    val error: String? = null,
    val code: String? = null,
    val message: String? = null,
)

private val ERROR_JSON = Json { ignoreUnknownKeys = true }

/**
 * 解析 daemon 的 `{error?, code?, message?}` 错误正文。
 *
 * SSE 失败路径（见 [com.airemote.network.airemote.AiremoteStream]）拿到的是非 2xx 的原始
 * 正文，而那个正文里的 `code` 是唯一能说明「为什么」的信息；两条路必须解析同一形状，
 * 所以解析器放这里共用。
 */
internal fun parseApiErrorBody(raw: String?): ApiErrorBody? {
    if (raw.isNullOrBlank()) return null
    return try {
        ERROR_JSON.decodeFromString<ApiErrorBody>(raw)
    } catch (_: Exception) {
        null
    }
}

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()

    /**
     * @param code HTTP 状态码（-1 网络不可达，-2 其他异常）。
     * @param message 可直接展示的文案，优先取后端 `error` 字段。
     * @param apiCode 后端错误码（如 `workspace_exists`），供业务层做精确映射。
     */
    data class Error(val code: Int, val message: String, val apiCode: String? = null) : NetworkResult<Nothing>()
}

suspend fun <T> safeApiCall(call: suspend () -> T): NetworkResult<T> = try {
    NetworkResult.Success(call())
} catch (e: HttpException) {
    val body = parseApiErrorBody(e.response()?.errorBody()?.string())
    NetworkResult.Error(
        code = e.code(),
        message = body?.error ?: body?.message ?: e.message() ?: "Http错误: ${e.code()}",
        apiCode = body?.code,
    )
} catch (e: IOException) {
    NetworkResult.Error(-1, e.message ?: "网络连接失败")
} catch (e: Exception) {
    NetworkResult.Error(-2, e.message ?: "未知错误")
}
