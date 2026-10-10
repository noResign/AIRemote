package com.noresign.paboot.updater.internal

import com.noresign.paboot.updater.UpdaterConfig
import com.noresign.paboot.updater.UpdateManifest

data class UpdateDecision(val updateAvailable: Boolean)

object UpdateDecisionHelper {
    fun decide(config: UpdaterConfig, manifest: UpdateManifest): UpdateDecision =
        UpdateDecision(updateAvailable = manifest.versionCode > config.currentVersionCode)
}
