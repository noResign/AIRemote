package com.airemote.airemote.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * 通知频道。Android 8+ 每条通知都必须归属一个 channel，重要性在 channel 上定死：
 * 审批是「现在就要你决定」，所以 HIGH（横幅 + 声音 + 振动）；任务结果同样 HIGH——任务完成也是
 * 值得打断的时刻（本来就是「丢给电脑去干别的」）；常驻的监听提示是 LOW（安静，不打扰）。
 *
 * 频道的重要性 / 声音 / 振动**只在首次创建时生效**，改了这里对已建过该 channel 的设备不起作用
 * ——要让新设置生效得卸载重装（或换个 channel id），否则只能由用户在系统通知设置里手动调。
 */
object NotificationChannels {

    /** agent 请求工具审批（Bash / 写文件 / MCP）。 */
    const val APPROVAL = "run_approval"

    /** 任务结束（完成 / 失败 / 取消）。 */
    const val RESULT = "run_result"

    /** 前台服务的常驻提示：客户端还在后台接收事件。 */
    const val WATCH = "run_watch"

    /** 提醒用的振动节奏（毫秒）：立即 → 振 250 → 停 250 → 振 250。 */
    val VIBRATION_PATTERN = longArrayOf(0, 250, 250, 250)

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(APPROVAL, "任务审批", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "agent 需要你允许或拒绝某个工具调用"
                    makeAlerting()
                },
                NotificationChannel(RESULT, "任务结果", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "任务完成、失败或被取消"
                    makeAlerting()
                },
                NotificationChannel(WATCH, "后台监听", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "有任务在跑时显示，表示客户端仍在接收事件"
                    setShowBadge(false)
                },
            )
        )
    }

    /** 声音 + 振动：重要性管「要不要横幅」，这两样得单独开。 */
    private fun NotificationChannel.makeAlerting() {
        setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AUDIO_ATTRIBUTES)
        enableVibration(true)
        vibrationPattern = VIBRATION_PATTERN
    }

    private val AUDIO_ATTRIBUTES: AudioAttributes =
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
}
