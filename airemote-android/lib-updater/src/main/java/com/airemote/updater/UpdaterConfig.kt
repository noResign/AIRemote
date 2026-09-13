package com.airemote.updater

import okhttp3.OkHttpClient

data class UpdaterConfig(
    val manifestUrl: String,
    val channel: String,
    val currentVersionCode: Long,
    val currentVersionName: String,
    val fileProviderAuthority: String,
    val client: OkHttpClient? = null,
)
