package com.noresign.paboot.viewmodel

import android.app.Application
import android.content.pm.PackageInfo
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noresign.paboot.data.update.AppUpdater
import com.noresign.paboot.updater.UpdateEvent
import com.noresign.paboot.updater.UpdateException
import com.noresign.paboot.updater.UpdateManifest
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class NoUpdate(val currentVersionName: String) : UpdateUiState
    data class Available(val manifest: UpdateManifest) : UpdateUiState
    data class Downloading(
        val manifest: UpdateManifest,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateUiState
    data class Downloaded(
        val apkFile: File,
        val manifest: UpdateManifest,
        /** 下载到的 APK 自身的版本，用于确认递交给系统安装器的确实是刚下发的包。 */
        val apkVersionCode: Long?,
        val apkVersionName: String?,
    ) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val updater = AppUpdater.updater

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState = _uiState.asStateFlow()

    fun check() {
        viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking
            updater.check().collect { event ->
                when (event) {
                    is UpdateEvent.Checking -> _uiState.value = UpdateUiState.Checking
                    is UpdateEvent.NoUpdate -> _uiState.value = UpdateUiState.NoUpdate(
                        currentVersionName = AppUpdater.config.currentVersionName,
                    )
                    is UpdateEvent.UpdateAvailable -> _uiState.value = UpdateUiState.Available(
                        manifest = event.manifest,
                    )
                    is UpdateEvent.Failed -> _uiState.value = UpdateUiState.Error(
                        message = event.error.message ?: "检查更新失败",
                    )
                    else -> Unit
                }
            }
        }
    }

    fun download() {
        val manifest = (_uiState.value as? UpdateUiState.Available)?.manifest ?: return
        viewModelScope.launch {
            updater.download(manifest, File(getApplication<Application>().cacheDir, "updates")).collect { event ->
                when (event) {
                    is UpdateEvent.DownloadProgress -> _uiState.value = UpdateUiState.Downloading(
                        manifest = manifest,
                        downloadedBytes = event.downloadedBytes,
                        totalBytes = event.totalBytes,
                    )
                    is UpdateEvent.Downloaded -> {
                        val apk = inspectApk(event.apkFile)
                        val apkVersionCode = apk?.let { PackageInfoCompat.getLongVersionCode(it) }
                        Log.i(
                            TAG,
                            "downloaded apk: ${event.apkFile.absolutePath} package=${apk?.packageName} " +
                                "versionCode=$apkVersionCode versionName=${apk?.versionName} " +
                                "manifestVersionCode=${event.manifest.versionCode}",
                        )
                        _uiState.value = UpdateUiState.Downloaded(
                            apkFile = event.apkFile,
                            manifest = event.manifest,
                            apkVersionCode = apkVersionCode,
                            apkVersionName = apk?.versionName,
                        )
                    }
                    is UpdateEvent.Failed -> _uiState.value = UpdateUiState.Error(
                        message = event.error.message ?: "下载更新失败",
                    )
                    else -> Unit
                }
            }
        }
    }

    fun install() {
        val downloaded = _uiState.value as? UpdateUiState.Downloaded ?: return
        try {
            updater.install(getApplication(), downloaded.apkFile)
            _uiState.value = UpdateUiState.Idle
        } catch (e: UpdateException) {
            _uiState.value = UpdateUiState.Error(message = e.message ?: "安装失败")
        }
    }

    fun retry() {
        check()
    }

    fun dismiss() {
        _uiState.value = UpdateUiState.Idle
    }

    private fun inspectApk(apkFile: File): PackageInfo? =
        getApplication<Application>().packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)

    private companion object {
        const val TAG = "PabootUpdater"
    }
}
