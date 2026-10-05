package com.airemote.airemote

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.airemote.airemote.data.update.AppUpdater
import com.airemote.airemote.notify.ChatVisibility
import com.airemote.airemote.notify.NotificationChannels
import com.airemote.airemote.notify.RunWatchCenter
import com.tencent.mmkv.MMKV

class AppApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        AppUpdater.cleanup(this)
        NotificationChannels.ensure(this)

        // 通知的抑制条件之一是「应用在前台」：后台（锁屏 / 切走）才算「人没在看」
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                ChatVisibility.appInForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                ChatVisibility.appInForeground = false
            }
        })

        // 恢复上次没跑完的后台监听（进程被杀后重建时走这里）
        RunWatchCenter.init(this)
    }
}
