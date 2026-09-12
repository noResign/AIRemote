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
    private const val KEY_SELECTED_WORKSPACE_ID = "selected_workspace_id"
    private const val KEY_SELECTED_WORKSPACE_PATH = "selected_workspace_path"

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
}
