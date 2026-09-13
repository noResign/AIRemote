package com.airemote.airemote

import android.app.Application
import com.airemote.airemote.data.update.AppUpdater
import com.tencent.mmkv.MMKV

class AppApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        AppUpdater.cleanup(this)

    }
}