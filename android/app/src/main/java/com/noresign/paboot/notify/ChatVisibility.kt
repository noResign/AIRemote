package com.noresign.paboot.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「用户此刻是不是正看着某个会话」——后台通知唯一的抑制条件。
 *
 * 进程前后台由 `ProcessLifecycleOwner` 在 `AppApplication` 里写入 [appInForeground]；
 * 可见会话由 `ChatViewModel` 进会话 / 拿到 sessionId 时写入，`onCleared` 时清空
 * （ViewModel 生命周期 == 聊天页生命周期，包括从空 sessionId 解析出真实 id 的新会话）。
 *
 * 两个条件都满足才认为用户「已经看到了」，此时不打扰。审批请求同样按这个规则处理：
 * 用户正开着会话页时弹过通知，就等于已经看到了那张审批卡。
 */
object ChatVisibility {

    private val _visibleSessionId = MutableStateFlow<String?>(null)
    val visibleSessionId = _visibleSessionId.asStateFlow()

    @Volatile
    var appInForeground: Boolean = false

    fun enter(sessionId: String?) {
        _visibleSessionId.value = sessionId
    }

    fun exit(sessionId: String?) {
        if (_visibleSessionId.value == sessionId) _visibleSessionId.value = null
    }

    fun isViewing(sessionId: String?): Boolean =
        appInForeground && sessionId != null && _visibleSessionId.value == sessionId
}
