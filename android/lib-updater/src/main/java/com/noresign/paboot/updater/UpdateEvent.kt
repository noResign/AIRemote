package com.noresign.paboot.updater

sealed interface UpdateEvent {
    data object Checking : UpdateEvent
    data object NoUpdate : UpdateEvent
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateEvent
    data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) : UpdateEvent
    data class Downloaded(val apkFile: java.io.File, val manifest: UpdateManifest) : UpdateEvent
    data class Failed(val error: UpdateException) : UpdateEvent
}
