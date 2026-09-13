package com.airemote.updater.internal

import com.airemote.updater.UpdaterConfig
import com.airemote.updater.UpdateManifest

data class UpdateDecision(val updateAvailable: Boolean, val forced: Boolean)

object UpdateDecisionHelper {
    fun decide(config: UpdaterConfig, manifest: UpdateManifest): UpdateDecision {
        val available = manifest.versionCode > config.currentVersionCode
        val forced = available && manifest.minVersionCode?.let { config.currentVersionCode < it } == true
        return UpdateDecision(updateAvailable = available, forced = forced)
    }
}
