package com.airemote.updater.internal

import com.airemote.updater.UpdaterConfig
import com.airemote.updater.UpdateManifest

data class UpdateDecision(val updateAvailable: Boolean)

object UpdateDecisionHelper {
    fun decide(config: UpdaterConfig, manifest: UpdateManifest): UpdateDecision =
        UpdateDecision(updateAvailable = manifest.versionCode > config.currentVersionCode)
}
