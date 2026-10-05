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
    private const val KEY_NAME = "daemon_name"
    private const val KEY_WORKSPACE = "daemon_workspace"
    private const val KEY_SELECTED_WORKSPACE_ID = "selected_workspace_id"
    private const val KEY_SELECTED_WORKSPACE_PATH = "selected_workspace_path"
    private const val KEY_SAVED_CONNECTIONS = "saved_connections"
    private const val KEY_BACKGROUND_NOTIFICATIONS = "background_notifications"
    private const val KEY_NOTIFICATION_PROMPT_SHOWN = "notification_prompt_shown"

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

    /** 上次使用的可读名称（连接成功后写入，用于连接页预填与列表展示）。 */
    var name: String?
        get() = kv.decodeString(KEY_NAME)
        set(value) {
            if (value.isNullOrBlank()) kv.removeValueForKey(KEY_NAME) else kv.encode(KEY_NAME, value)
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
     *
     * 字段顺序：`name\tbaseUrl\ttoken`。旧版本只存 `baseUrl\ttoken`（两段），
     * 解析时按段数兼容，旧记录 name 视为空。
     */
    val savedConnections: List<SavedConnection>
        get() = kv.decodeString(KEY_SAVED_CONNECTIONS)
            .orEmpty()
            .split(ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val parts = entry.split(FIELD_SEPARATOR)
                when {
                    parts.size == 3 && parts[1].isNotBlank() && parts[2].isNotBlank() ->
                        SavedConnection(baseUrl = parts[1], token = parts[2], name = parts[0])
                    parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() ->
                        SavedConnection(baseUrl = parts[0], token = parts[1])
                    else -> null
                }
            }

    /**
     * 后台任务提醒（审批 / 完成时弹系统通知）。
     *
     * 关掉后不再建立后台监听连接，也不会有常驻的「正在监听」提示；任务本身照旧在电脑上跑。
     * 默认开：这个功能的价值就在「人不在会话页」的时候，默认关等于没做。
     */
    var backgroundNotifications: Boolean
        get() = !kv.containsKey(KEY_BACKGROUND_NOTIFICATIONS) || kv.decodeBool(KEY_BACKGROUND_NOTIFICATIONS)
        set(value) {
            kv.encode(KEY_BACKGROUND_NOTIFICATIONS, value)
        }

    /** 是否已经弹过系统通知权限框。只弹一次，之后由设置页引导去系统设置。 */
    var notificationPromptShown: Boolean
        get() = kv.decodeBool(KEY_NOTIFICATION_PROMPT_SHOWN)
        set(value) {
            kv.encode(KEY_NOTIFICATION_PROMPT_SHOWN, value)
        }

    /** 记录一次成功的连接：同一地址只保留最新一条（token 轮换或改名后自动覆盖）。 */
    fun rememberConnection(name: String, baseUrl: String, token: String) {
        val nm = sanitizeField(name)
        val url = baseUrl.trim()
        val tk = token.trim()
        if (url.isEmpty() || tk.isEmpty()) return
        val updated = listOf(SavedConnection(url, tk, nm)) +
            savedConnections.filterNot { it.baseUrl == url }
        writeSavedConnections(updated.take(MAX_SAVED_CONNECTIONS))
    }

    fun forgetConnection(baseUrl: String) {
        writeSavedConnections(savedConnections.filterNot { it.baseUrl == baseUrl })
    }

    private fun writeSavedConnections(connections: List<SavedConnection>) {
        val encoded = connections.joinToString(ENTRY_SEPARATOR) {
            "${sanitizeField(it.name)}$FIELD_SEPARATOR${it.baseUrl}$FIELD_SEPARATOR${it.token}"
        }
        if (encoded.isBlank()) {
            kv.removeValueForKey(KEY_SAVED_CONNECTIONS)
        } else {
            kv.encode(KEY_SAVED_CONNECTIONS, encoded)
        }
    }

    /** name 是自由输入，不能混入分隔符，否则会破坏编码。 */
    private fun sanitizeField(value: String): String =
        value.replace(ENTRY_SEPARATOR, " ").replace(FIELD_SEPARATOR, " ").trim()
}
