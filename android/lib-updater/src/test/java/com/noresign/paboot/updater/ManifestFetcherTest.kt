package com.noresign.paboot.updater

import com.noresign.paboot.updater.internal.ManifestFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestFetcherTest {

    private fun manifestJson(channel: String = "alpha", apkUrl: String = "https://example.com/app.apk"): String =
        """
        {
          "channel": "$channel",
          "versionCode": 11,
          "versionName": "0.2.0",
          "apkUrl": "$apkUrl",
          "apkSize": 100,
          "sha256": "${"a".repeat(64)}"
        }
        """.trimIndent()

    @Test
    fun `fetches and validates manifest`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(manifestJson()))
        server.start()
        try {
            val config = UpdaterConfig(
                manifestUrl = server.url("/manifest.json").toString(),
                channel = "alpha",
                currentVersionCode = 10,
                currentVersionName = "0.1.0",
                fileProviderAuthority = "com.noresign.paboot.fileprovider",
            )
            val fetcher = ManifestFetcher(config, OkHttpClient())
            val manifest = runBlocking { fetcher.fetch() }
            assertEquals(11L, manifest.versionCode)
            val request = server.takeRequest()
            assertNotNull(request.requestUrl?.queryParameter("_ts"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejects channel mismatch`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(manifestJson(channel = "prod")))
        server.start()
        try {
            val config = UpdaterConfig(
                manifestUrl = server.url("/manifest.json").toString(),
                channel = "alpha",
                currentVersionCode = 10,
                currentVersionName = "0.1.0",
                fileProviderAuthority = "com.noresign.paboot.fileprovider",
            )
            val fetcher = ManifestFetcher(config, OkHttpClient())
            assertThrows(UpdateException.ChannelMismatch::class.java) {
                runBlocking { fetcher.fetch() }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejects non-local http apk url`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(manifestJson(apkUrl = "http://example.com/app.apk")))
        server.start()
        try {
            val config = UpdaterConfig(
                manifestUrl = server.url("/manifest.json").toString(),
                channel = "alpha",
                currentVersionCode = 10,
                currentVersionName = "0.1.0",
                fileProviderAuthority = "com.noresign.paboot.fileprovider",
            )
            val fetcher = ManifestFetcher(config, OkHttpClient())
            assertThrows(UpdateException.ManifestInvalid::class.java) {
                runBlocking { fetcher.fetch() }
            }
        } finally {
            server.shutdown()
        }
    }
}
