package com.airemote.airemote.notify

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.airemote.airemote.MainActivity
import com.airemote.airemote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 只做一件事的前台服务：**让进程在后台活着**，好让 [RunWatchCenter] 的监听连接不被系统回收。
 *
 * 没有它的话，锁屏几分钟后进程进入缓存态就被杀了，SSE 一断，「任务完成 / 需要审批」的通知
 * 就永远弹不出来——而审批在 daemon 侧是超时自动拒绝的，用户根本没机会响应。
 *
 * 生命周期：有 run 要监听才启动，最后一个 run 收口时自停（见 [RunWatchCenter.settle]）。
 * 这意味着它不会常驻——只在真的有事在跑的时候出现。
 */
class RunWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // 常驻通知的文案跟着监听数量走
        observeJob = scope.launch {
            RunWatchCenter.watched.collect { updateNotification(it.size) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内 startForeground，否则系统会抛 ForegroundServiceDidNotStartInTimeException
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(RunWatchCenter.watched.value.size),
            foregroundServiceType(),
        )
        if (RunWatchCenter.watched.value.isEmpty()) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 只刷新文案，不停服务：停止统一走 [onStartCommand]（空列表）或
     * `RunWatchCenter` 的收口逻辑，保证「先 startForeground 再 stopSelf」的顺序。
     */
    private fun updateNotification(count: Int) {
        if (count <= 0) return
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(count), foregroundServiceType())
        }
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private fun buildNotification(count: Int): Notification {
        val text = if (count > 0) {
            "正在监听 $count 个任务，完成或需要审批时通知你"
        } else {
            "正在后台接收任务事件"
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.WATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AIRemote")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x1A1

        /**
         * 启动监听服务。Android 12+ 禁止从后台启动前台服务，被系统拒绝时静默降级：
         * 监听仍然成立，只是进程可能被回收得更早。
         */
        fun start(context: Context) {
            val intent = Intent(context, RunWatchService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RunWatchService::class.java)) }
        }
    }
}
