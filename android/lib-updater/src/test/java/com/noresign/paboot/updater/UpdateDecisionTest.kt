package com.noresign.paboot.updater

import com.noresign.paboot.updater.internal.UpdateDecisionHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDecisionTest {

    private val config = UpdaterConfig(
        manifestUrl = "https://example.com/manifest.json",
        channel = "alpha",
        currentVersionCode = 10,
        currentVersionName = "10",
        fileProviderAuthority = "com.noresign.paboot.fileprovider",
    )

    private fun manifest(versionCode: Long) = UpdateManifest(
        channel = "alpha",
        versionCode = versionCode,
        versionName = versionCode.toString(),
        apkUrl = "https://example.com/app.apk",
        apkSize = 100,
        sha256 = "a".repeat(64),
    )

    @Test
    fun `newer version is available`() {
        val decision = UpdateDecisionHelper.decide(config, manifest(11))
        assertTrue(decision.updateAvailable)
    }

    @Test
    fun `same or older version is not available`() {
        assertFalse(UpdateDecisionHelper.decide(config, manifest(10)).updateAvailable)
        assertFalse(UpdateDecisionHelper.decide(config, manifest(9)).updateAvailable)
    }
}
