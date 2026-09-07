package com.airemote.airemote.data.local

import com.tencent.mmkv.MMKV

/**
 * Minimal persistence for the daemon connection settings, backed by MMKV
 * (initialized in [com.airemote.airemote.AppApplication]).
 */
object SettingsStore {

    private val kv: MMKV by lazy { MMKV.defaultMMKV() }

    private const val KEY_BASE_URL = "daemon_base_url"
    private const val KEY_TOKEN = "daemon_token"
    private const val KEY_WORKSPACE = "daemon_workspace"

    var baseUrl: String?
        get() = kv.decodeString(KEY_BASE_URL)
        set(value) {
            if (value.isNullOrBlank()) kv.removeValueForKey(KEY_BASE_URL) else kv.encode(KEY_BASE_URL, value)
        }

    var token: String?
        get() = kv.decodeString(KEY_TOKEN)
        set(value) {
            if (value.isNullOrBlank()) kv.removeValueForKey(KEY_TOKEN) else kv.encode(KEY_TOKEN, value)
        }

    /** daemon 主 workspace 根目录（连接成功后写入）。 */
    var workspace: String?
        get() = kv.decodeString(KEY_WORKSPACE)
        set(value) {
            if (value.isNullOrBlank()) kv.removeValueForKey(KEY_WORKSPACE) else kv.encode(KEY_WORKSPACE, value)
        }
}
