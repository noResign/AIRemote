package com.airemote.updater

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.airemote.updater.internal.ApkVerifier
import com.airemote.updater.internal.ManifestFetcher
import com.airemote.updater.internal.UpdateDecisionHelper
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class Updater(private val config: UpdaterConfig) {

    private val client = config.client ?: OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val manifestFetcher = ManifestFetcher(config, client, json)

    /** 检查更新：manifest → channel 校验 → versionCode 比较。 */
    fun check(): Flow<UpdateEvent> = flow {
        emit(UpdateEvent.Checking)
        val manifest = manifestFetcher.fetch()
        val decision = UpdateDecisionHelper.decide(config, manifest)
        if (decision.updateAvailable) {
            emit(UpdateEvent.UpdateAvailable(manifest, decision.forced))
        } else {
            emit(UpdateEvent.NoUpdate)
        }
    }.catch { throwable ->
        emit(UpdateEvent.Failed(UpdateException.from(throwable)))
    }

    /** 检查更新，如果有新版本则继续下载。 */
    fun checkAndDownload(destDir: File): Flow<UpdateEvent> = flow {
        emit(UpdateEvent.Checking)
        val manifest = manifestFetcher.fetch()
        val decision = UpdateDecisionHelper.decide(config, manifest)
        if (!decision.updateAvailable) {
            emit(UpdateEvent.NoUpdate)
        } else {
            emit(UpdateEvent.UpdateAvailable(manifest, decision.forced))
            download(manifest, destDir).collect { emit(it) }
        }
    }.catch { throwable ->
        emit(UpdateEvent.Failed(UpdateException.from(throwable)))
    }

    /** 下载 APK 到 destDir/latest.apk.part，sha256 校验后重命名为 latest.apk。 */
    fun download(manifest: UpdateManifest, destDir: File): Flow<UpdateEvent> = flow {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw UpdateException.Storage("cannot create update dir: ${destDir.absolutePath}")
        }
        val part = File(destDir, "latest.apk.part")
        val target = File(destDir, "latest.apk")
        part.delete()
        try {
            val request = Request.Builder().url(manifest.apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException.Network("apk request failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw UpdateException.Network("apk response body is empty")
                val total = body.contentLength().takeIf { it > 0 } ?: manifest.apkSize
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            emit(UpdateEvent.DownloadProgress(downloaded, total))
                        }
                    }
                }
            }
            if (part.length() != manifest.apkSize) {
                throw UpdateException.ChecksumMismatch(
                    expected = "size ${manifest.apkSize}",
                    actual = "size ${part.length()}",
                )
            }
            val actualSha256 = ApkVerifier.sha256(part)
            if (!actualSha256.equals(manifest.sha256, ignoreCase = true)) {
                throw UpdateException.ChecksumMismatch(manifest.sha256, actualSha256)
            }
            target.delete()
            if (!part.renameTo(target)) {
                throw UpdateException.Storage("failed to move apk into place")
            }
            emit(UpdateEvent.Downloaded(target, manifest))
        } catch (throwable: Throwable) {
            part.delete()
            throw throwable
        }
    }.flowOn(Dispatchers.IO).catch { throwable ->
        emit(UpdateEvent.Failed(UpdateException.from(throwable)))
    }

    /** 调起系统安装器；FileProvider 由宿主 App 声明。 */
    fun install(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) {
            throw UpdateException.InstallNotResolved("apk file not found: ${apkFile.absolutePath}")
        }
        val uri = FileProvider.getUriForFile(context, config.fileProviderAuthority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            throw UpdateException.InstallNotResolved("no package installer available", e)
        }
    }

    /** App 启动时清空上次遗留的安装包；不要在 install() 后立刻清理。 */
    fun cleanup(context: Context) {
        File(context.cacheDir, "updates").deleteRecursively()
    }
}
