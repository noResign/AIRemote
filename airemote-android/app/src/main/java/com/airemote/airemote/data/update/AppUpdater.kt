package com.airemote.airemote.data.update

import android.content.Context
import com.airemote.airemote.BuildConfig
import com.airemote.updater.Updater
import com.airemote.updater.UpdaterConfig

object AppUpdater {

    /** manifest 地址来自本地 updater.properties；公开构建里为空，表示不启用更新通道。 */
    val enabled: Boolean = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank()

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
