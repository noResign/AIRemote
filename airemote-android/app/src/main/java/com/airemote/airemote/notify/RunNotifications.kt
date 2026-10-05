package com.airemote.airemote.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.airemote.airemote.R

/**
 * 任务相关系统通知的构建与发送。
 *
 * 审批与结果都**按会话聚合**（通知 id 由 sessionId 派生）：同一个会话只留最新一条，
 * 不会堆成一片；点开直接进该会话——正在等的审批卡和最新回复都在那儿。
 *
 * 后期接定时任务时，复用同一个入口即可：`postApproval` / `postResult` 拿的只是
 * 「会话 + 标题 + 文案」，与 run 从哪来无关。
 */
object RunNotifications {

    /** 通知里携带的会话 id，[DeepLinkActivity] 读它做深链跳转。 */
    const val EXTRA_SESSION_ID = "com.airemote.airemote.extra.SESSION_ID"

    fun postApproval(
        context: Context,
        sessionId: String,
        title: String?,
        toolName: String,
        detail: String?,
    ) {
        val text = detail?.takeIf { it.isNotBlank() }
            ?.let { "$toolName：$it" }
            ?: "$toolName 等待你的允许或拒绝"
        val notification = NotificationCompat.Builder(context, NotificationChannels.APPROVAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(headline("需要审批", title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .applyAlertCompat()
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, sessionId))
            .build()
        notify(context, idFor(sessionId), notification)
    }

    /**
     * 任务结束通知。[label] 来自 daemon 的终局 `status.label`（succeeded / failed / cancelled）。
     */
    fun postResult(context: Context, sessionId: String, title: String?, label: String, message: String?) {
        val detail = when (label) {
            "succeeded" -> "点击查看结果"
            "failed" -> message?.takeIf { it.isNotBlank() } ?: "点击查看详情"
            "cancelled" -> "点击回到会话"
            else -> "点击回到会话"
        }
        val headline = when (label) {
            "succeeded" -> "任务完成"
            "failed" -> "任务失败"
            "cancelled" -> "任务已取消"
            else -> "任务结束"
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(headline(headline, title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .applyAlertCompat()
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, sessionId))
            .build()
        notify(context, idFor(sessionId), notification)
    }

    /**
     * Android O 以下没有 channel，声音 / 振动得挂在通知自己身上；O+ 会忽略这两项、只看 channel
     * （见 [NotificationChannels]）。所以这里对两种系统都安全。
     */
    private fun NotificationCompat.Builder.applyAlertCompat(): NotificationCompat.Builder = apply {
        setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
        setVibrate(NotificationChannels.VIBRATION_PATTERN)
    }

    /** 用户进会话 / 已读时清掉该会话的通知。 */
    fun clear(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(idFor(sessionId))
    }

    fun clearAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun headline(prefix: String, title: String?): String =
        title?.takeIf { it.isNotBlank() }?.let { "$prefix · $it" } ?: prefix

    /** 会话 id → 通知 id：同一会话的通知互相覆盖，而不是越堆越多。 */
    private fun idFor(sessionId: String): Int = sessionId.hashCode() and 0x7fffffff

    private fun notify(context: Context, id: Int, notification: Notification) {
        // 权限被拒 / 被系统关掉时静默放弃：任务本身继续跑，只是不打扰用户
        if (!NotificationPermission.isGranted(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun contentIntent(context: Context, sessionId: String): PendingIntent {
        // 目标必须是 Activity（getActivity）：通知点击的「后台启动豁免」只覆盖 PendingIntent
        // 本身指向的启动，Android 12+ 更明确禁止经 Service/Receiver 中转的 trampoline。
        // 这里是透明的 DeepLinkActivity，它再拉起主界面——第二跳由已可见的 Activity 发起，不受限。
        val intent = Intent(context, DeepLinkActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        return PendingIntent.getActivity(
            context,
            idFor(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
