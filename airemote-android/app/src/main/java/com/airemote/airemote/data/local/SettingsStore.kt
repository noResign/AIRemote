package com.airemote.airemote.data.local

import com.airemote.airemote.data.SavedConnection
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
    private const val KEY_SELECTED_WORKSPACE_ID = "selected_workspace_id"
    private const val KEY_SELECTED_WORKSPACE_PATH = "selected_workspace_path"
    private const val KEY_SAVED_CONNECTIONS = "saved_connections"

    private const val MAX_SAVED_CONNECTIONS = 5
    private const val ENTRY_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = "\t"

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

    /** daemon 默认 Workspace 根目录（连接成功后写入，仅作兼容/兜底展示）。 */
    var workspace: String?
        get() = kv.decodeString(KEY_WORKSPACE)
        set(value) {
            if (value.isNullOrBlank()) kv.removeValueForKey(KEY_WORKSPACE) else kv.encode(KEY_WORKSPACE, value)
        }

    /** 当前客户端选中的 Workspace id。 */
    var selectedWorkspaceId: String?
        get() = kv.decodeString(KEY_SELECTED_WORKSPACE_ID)
        set(value) {
            if (value.isNullOrBlank()) kv.removeValueForKey(KEY_SELECTED_WORKSPACE_ID) else kv.encode(KEY_SELECTED_WORKSPACE_ID, value)
        }

    /** 当前客户端选中的 Workspace path（仅用于界面展示/兜底）。 */
    var selectedWorkspacePath: String?
        get() = kv.decodeString(KEY_SELECTED_WORKSPACE_PATH)
        set(value) {
            if (value.isNullOrBlank()) kv.removeValueForKey(KEY_SELECTED_WORKSPACE_PATH) else kv.encode(KEY_SELECTED_WORKSPACE_PATH, value)
        }

    /**
     * 最近连接成功过的服务器，最新在前。
     *
     * 存成一条字符串（条目用 `\n`、字段用 `\t` 分隔）而不是 MMKV 的 StringSet：
     * 顺序即"最近优先"，必须保留。
     */
    val savedConnections: List<SavedConnection>
        get() = kv.decodeString(KEY_SAVED_CONNECTIONS)
            .orEmpty()
            .split(ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val parts = entry.split(FIELD_SEPARATOR)
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    null
                } else {
                    SavedConnection(baseUrl = parts[0], token = parts[1])
                }
            }

    /** 记录一次成功的连接：同一地址只保留最新一条（token 轮换后自动覆盖）。 */
    fun rememberConnection(baseUrl: String, token: String) {
        val url = baseUrl.trim()
        val tk = token.trim()
        if (url.isEmpty() || tk.isEmpty()) return
        val updated = listOf(SavedConnection(url, tk)) + savedConnections.filterNot { it.baseUrl == url }
        writeSavedConnections(updated.take(MAX_SAVED_CONNECTIONS))
    }

    fun forgetConnection(baseUrl: String) {
        writeSavedConnections(savedConnections.filterNot { it.baseUrl == baseUrl })
    }

    private fun writeSavedConnections(connections: List<SavedConnection>) {
        val encoded = connections.joinToString(ENTRY_SEPARATOR) {
            "${it.baseUrl}$FIELD_SEPARATOR${it.token}"
        }
        if (encoded.isBlank()) {
            kv.removeValueForKey(KEY_SAVED_CONNECTIONS)
        } else {
            kv.encode(KEY_SAVED_CONNECTIONS, encoded)
        }
    }
}
