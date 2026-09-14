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
private data class ApiErrorBody(
    val error: String? = null,
    val code: String? = null,
    val message: String? = null,
)

private val ERROR_JSON = Json { ignoreUnknownKeys = true }

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
    val body = e.response()?.errorBody()?.let { raw ->
        try {
            ERROR_JSON.decodeFromString<ApiErrorBody>(raw.string())
        } catch (_: Exception) {
            null
        }
    }
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
