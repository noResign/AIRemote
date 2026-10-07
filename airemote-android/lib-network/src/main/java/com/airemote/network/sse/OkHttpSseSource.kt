package com.airemote.network.sse

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 用**裸 OkHttp 长连接**实现的 [SseSource]（不依赖 `okhttp-sse`）。
 *
 * 做法：`call.execute()` 拿响应体，`readUtf8Line()` 逐行读，喂给 [SseParser]，
 * 解析出的事件用挂起版 `send` 送到 `Flow`（背压防丢帧）。阻塞读放在 [Dispatchers.IO]；
 * collector 取消时由 `awaitClose` 里 `call.cancel()` 打断阻塞读。
 */
class OkHttpSseSource(
    private val client: OkHttpClient = defaultSseClient(),
) : SseSource {

    override fun events(request: Request): Flow<SseEvent> = callbackFlow {
        val scope = this
        val call = client.newCall(request)
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        // daemon 在非 2xx 正文里给出 {error, code}；不读走，上层只能显示
                        // 一个 HTTP 状态码，用户看不出失败原因（例如「Codex 没装」）。
                        val body = runCatching { response.body?.string() }.getOrNull()
                        throw SseException(response.code, "HTTP ${response.code}", body = body)
                    }
                    val source = response.body?.source()
                        ?: throw SseException(response.code, "empty response body")

                    val parser = SseParser()
                    while (isActive) {
                        val line = source.readUtf8Line() ?: break
                        parser.feed(line)?.let { scope.send(it) }
                    }
                    // 服务端没以空行收尾就断开时，冲掉残留
                    parser.flush()?.let { scope.send(it) }
                }
            } catch (e: SseException) {
                if (isActive) scope.close(e)
            } catch (e: IOException) {
                // call.cancel()（collector 取消）也会走到这里；此时 channel 已关，close 无害
                if (isActive) scope.close(SseException(httpCode = null, message = e.message ?: "SSE 连接失败", cause = e))
            } finally {
                // 正常读完（服务端 res.end）也走这里：关掉 channel → 上游 Flow 正常结束
                scope.close()
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }
}

private val sharedSseClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 读超时不能是 0：断网（wifi 掉线、锁屏、隧道瞬断）后阻塞读永不返回，上层连
        // 「连接已死」这个信号都拿不到，自动重连也就无从触发。
        .readTimeout(SSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
}

/**
 * SSE 读超时。**与 daemon 的 keepalive 间隔（15 秒）绑定**：两条 SSE 端点都每 15 秒写一行
 * `: keepalive`，所以 45 秒（连续 3 次收不到任何字节）足以判定死链。改动任一边都要同步改
 * 另一边（`airemote-daemon/src/routes/chat.ts`、`routes/runs.ts` 里的 `setInterval`）。
 */
private const val SSE_READ_TIMEOUT_SECONDS = 45L

/** [OkHttpSseSource] 的默认长连接客户端；构造时可注入替换（测试 / 复用）。 */
fun defaultSseClient(): OkHttpClient = sharedSseClient
