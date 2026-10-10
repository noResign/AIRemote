package com.noresign.paboot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.noresign.paboot.navigation.AppNavHost
import com.noresign.paboot.notify.PendingDeepLink
import com.noresign.paboot.ui.theme.PabootTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PabootTheme {
                // 通知点进来要打开的会话（由 DeepLinkActivity 写入）；由 AppNavHost 消费
                // （可能要先过连接页）。冷 / 热启动都走这条 StateFlow，不碰 Activity 的 intent。
                val pending by PendingDeepLink.sessionId.collectAsState()
                AppNavHost(
                    pendingSessionId = pending,
                    onPendingConsumed = { PendingDeepLink.consume() },
                )
            }
        }
    }
}
