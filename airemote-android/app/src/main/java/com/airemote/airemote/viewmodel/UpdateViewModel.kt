package com.airemote.airemote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airemote.airemote.data.update.AppUpdater
import com.airemote.updater.UpdateEvent
import com.airemote.updater.UpdateException
import com.airemote.updater.UpdateManifest
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Checking(val manual: Boolean) : UpdateUiState
    data class NoUpdate(val manual: Boolean, val currentVersionName: String) : UpdateUiState
    data class Available(val manifest: UpdateManifest, val forced: Boolean) : UpdateUiState
    data class Downloading(
        val manifest: UpdateManifest,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateUiState
    data class Downloaded(val apkFile: File, val manifest: UpdateManifest) : UpdateUiState
    data class Error(val manual: Boolean, val message: String) : UpdateUiState
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val updater = AppUpdater.updater

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState = _uiState.asStateFlow()

    fun check(manual: Boolean = true) {
        viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking(manual)
            updater.check().collect { event ->
                when (event) {
                    is UpdateEvent.Checking -> _uiState.value = UpdateUiState.Checking(manual)
                    is UpdateEvent.NoUpdate -> _uiState.value = UpdateUiState.NoUpdate(
                        manual = manual,
                        currentVersionName = AppUpdater.config.currentVersionName,
                    )
                    is UpdateEvent.UpdateAvailable -> _uiState.value = UpdateUiState.Available(
                        manifest = event.manifest,
                        forced = event.forced,
                    )
                    is UpdateEvent.Failed -> _uiState.value = UpdateUiState.Error(
                        manual = manual,
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
                    is UpdateEvent.Downloaded -> _uiState.value = UpdateUiState.Downloaded(
                        apkFile = event.apkFile,
                        manifest = event.manifest,
                    )
                    is UpdateEvent.Failed -> _uiState.value = UpdateUiState.Error(
                        manual = true,
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
            _uiState.value = UpdateUiState.Error(manual = true, message = e.message ?: "安装失败")
        }
    }

    fun retry() {
        check(manual = true)
    }

    fun dismiss() {
        _uiState.value = UpdateUiState.Idle
    }
}
