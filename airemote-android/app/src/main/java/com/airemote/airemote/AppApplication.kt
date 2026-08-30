package com.airemote.airemote

import android.app.Application
import com.tencent.mmkv.MMKV

class AppApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)

    }
}