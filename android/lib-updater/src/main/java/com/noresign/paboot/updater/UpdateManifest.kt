package com.noresign.paboot.updater

import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val changelog: String? = null,
    val publishedAt: String? = null,
)
