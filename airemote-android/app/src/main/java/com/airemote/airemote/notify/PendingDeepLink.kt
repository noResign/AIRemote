package com.airemote.airemote.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知点进来要打开的会话，应用级单次消费。
 *
 * 写入方是 [DeepLinkActivity]——通知的 `PendingIntent` 指向它，它解析出目标会话后写进这里，
 * 再把主界面拉起来。冷启动（进程被杀）与热启动（Activity 停在后台）走的是同一条路径，
 * 所以 `MainActivity` 不用碰 intent，Compose 侧只认这条 StateFlow。
 */
object PendingDeepLink {

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId = _sessionId.asStateFlow()

    fun open(sessionId: String) {
        if (sessionId.isNotBlank()) _sessionId.value = sessionId
    }

    /** 导航层消费掉之后清空，避免返回时又跳一次。 */
    fun consume() {
        _sessionId.value = null
    }
}
