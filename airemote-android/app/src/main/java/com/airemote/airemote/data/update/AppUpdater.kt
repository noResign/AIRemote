package com.airemote.airemote.data.update

import android.content.Context
import com.airemote.airemote.BuildConfig
import com.airemote.updater.Updater
import com.airemote.updater.UpdaterConfig

object AppUpdater {

    val config = UpdaterConfig(
        manifestUrl = BuildConfig.UPDATE_MANIFEST_URL,
        channel = BuildConfig.UPDATE_CHANNEL,
        currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
        currentVersionName = BuildConfig.VERSION_NAME,
        fileProviderAuthority = "${BuildConfig.APPLICATION_ID}.fileprovider",
    )

    val updater = Updater(config)

    fun cleanup(context: Context) {
        updater.cleanup(context)
    }
}
