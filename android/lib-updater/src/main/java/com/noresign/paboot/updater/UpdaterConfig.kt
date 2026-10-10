package com.noresign.paboot.updater

import okhttp3.OkHttpClient

data class UpdaterConfig(
    val manifestUrl: String,
    val channel: String,
    val currentVersionCode: Long,
    val currentVersionName: String,
    val fileProviderAuthority: String,
    val client: OkHttpClient? = null,
)
