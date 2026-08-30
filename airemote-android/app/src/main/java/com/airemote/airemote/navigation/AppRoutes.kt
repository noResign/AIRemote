package com.airemote.airemote.navigation

import androidx.navigation.NavController

object AppRoutes {

    const val CONNECT = "connect"
    const val MAIN = "main"

    /** 聊天详情；`sessionId` 为空 = 新会话。 */
    const val CONVERSATION = "conversation?sessionId={sessionId}"

    const val FILE = "file"
    const val SETTING = "setting"

    fun conversation(sessionId: String?): String =
        if (sessionId.isNullOrBlank()) "conversation" else "conversation?sessionId=$sessionId"
}

fun NavController.navigateToMain() {
    navigate(AppRoutes.MAIN) {
        popUpTo(AppRoutes.CONNECT) { inclusive = true }
    }
}

fun NavController.navigateToConversation(sessionId: String?) {
    navigate(AppRoutes.conversation(sessionId))
}

fun NavController.navigateToSettings() {
    navigate(AppRoutes.SETTING)
}

fun NavController.navigateToConnect() {
    navigate(AppRoutes.CONNECT) {
        // 设置页一定是从会话列表（MAIN）进来的，清掉 MAIN 及其之上的返回栈，回到连接门禁
        popUpTo(AppRoutes.MAIN) { inclusive = true }
    }
}
