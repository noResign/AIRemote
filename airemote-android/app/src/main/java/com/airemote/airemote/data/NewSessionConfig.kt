package com.airemote.airemote.data

/** 新建会话时选择的配置（续接 Claude 会话 / runtime）。 */
data class NewSessionConfig(
    val claudeSessionId: String? = null,
    val runtime: String? = null,
)

/**
 * 「新建会话」Sheet 与聊天页之间的临时传递：Sheet 写入 → 导航到新会话 → 聊天页读取并清空。
 */
object PendingNewSession {
    @Volatile
    private var config: NewSessionConfig? = null

    fun set(claudeSessionId: String? = null, runtime: String? = null) {
        config = NewSessionConfig(claudeSessionId, runtime)
    }

    fun take(): NewSessionConfig? {
        val c = config
        config = null
        return c
    }
}
