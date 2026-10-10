package com.noresign.paboot.notify

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.noresign.paboot.MainActivity

/**
 * 通知深链的中转站：解析目标会话 → 写进 [PendingDeepLink] → 拉起主界面 → 自己结束。
 * 没有 UI（透明主题 `Theme.paboot.DeepLink`），用户看不到这一跳。
 *
 * 为什么独立成一个 Activity：Android 12+ 禁止「通知 → Service / BroadcastReceiver → Activity」
 * 这种 trampoline——通知的 PendingIntent 必须直接指向 Activity。指向本 Activity 之后，第二跳
 * 由已经可见的 [DeepLinkActivity] 发起，也就不受后台启动 Activity 的限制。
 *
 * 之后要接外部深链（自定义 scheme / App Links）、推送、快捷方式，也都可以收敛到这里解析。
 */
class DeepLinkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 目标会话交给导航层；MainActivity 自己不碰 intent（冷 / 热启动同一条路径）
        intent.getStringExtra(RunNotifications.EXTRA_SESSION_ID)?.let(PendingDeepLink::open)
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        )
        finish()
    }
}
