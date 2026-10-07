package com.airemote.airemote.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.airemote.airemote.ui.identity.RuntimeIdentities
import com.airemote.network.airemote.dto.NormalizedEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun PermissionDialog(
    permission: NormalizedEvent.PermissionRequest,
    runtime: String? = null,
    submitting: Boolean = false,
    /** Server-side rejection of a previous answer (`bad_response`), shown in the input dialog. */
    inputError: String? = null,
    onDecide: (String, String?, JsonElement?) -> Unit,
) {
    if (permission.toolName == "UserInput") {
        UserInputDialog(permission, submitting, inputError, onDecide)
        return
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${runtime?.let { RuntimeIdentities.of(it).displayName } ?: "Agent"} 请求执行", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = toolLabel(permission.toolName),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                ) {
                    Text(
                        text = permissionBody(permission.toolName, permission.toolInput),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Text(
                    text = permissionWarning(permission.toolName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onDecide("deny", "用户拒绝", null) },
                        modifier = Modifier.weight(1f),
                        enabled = !submitting,
                    ) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = { onDecide("allow_all", null, null) },
                        modifier = Modifier.weight(1f),
                        enabled = !submitting,
                    ) { Text("允许全部") }
                    Button(
                        onClick = { onDecide("allow", null, null) },
                        modifier = Modifier.weight(1f),
                        enabled = !submitting,
                    ) { Text("允许") }
                }
            }
        }
    }
}

private const val MCP_PREFIX = "mcp__"

internal fun isMcpTool(toolName: String): Boolean = toolName.startsWith(MCP_PREFIX)

/**
 * Names the daemon synthesizes for asks that are not tool calls（Codex 的权限档案扩展
 * 和终端写入）。They arrive as `toolName`, so they need a label here or the card shows
 * the raw English identifier.
 */
private val SYNTHETIC_TOOL_LABELS = mapOf(
    "Permissions" to "权限扩展",
    "TerminalInput" to "终端输入",
)

/** `mcp__github__create_issue` → `github · create_issue`; other tool names unchanged. */
internal fun toolLabel(toolName: String): String =
    SYNTHETIC_TOOL_LABELS[toolName]
        ?: if (isMcpTool(toolName)) toolName.removePrefix(MCP_PREFIX).replace("__", " · ") else toolName

/** Warning under the body: what the tool can do, plus what 「允许全部」 actually grants. */
internal fun permissionWarning(toolName: String): String {
    if (toolName == "Permissions") return "这会扩大 Codex 的文件或网络访问范围，单次批准有效到当前轮结束。" +
        "「允许全部」还会自动批准本会话后续所有权限扩展，包括不同路径。撤销仅影响后续请求；立即收回请停止运行。"
    if (isMcpTool(toolName)) {
        return "这是 MCP Server 提供的工具，可能调用外部服务或产生副作用。" +
            "超时未处理将自动拒绝；「允许全部」在本 Session 内不再询问该 Server 的所有工具。"
    }
    val risk = when (toolName) {
        "Bash" -> "此命令可能修改文件或系统"
        "TerminalInput" -> "这是向一个正在运行的命令写入内容，实际影响取决于该命令"
        "Write" -> "此操作可能覆盖文件内容"
        "Edit", "MultiEdit" -> "此操作可能修改文件内容"
        else -> "此操作可能修改文件或系统"
    }
    return "$risk，请确认安全后再允许。超时未处理将自动拒绝；「允许全部」在本 Session 内不再询问该工具。"
}

internal fun commandText(input: JsonElement?): String {
    if (input is JsonObject) {
        val c = input["command"]
        if (c is JsonPrimitive && c.isString) return c.content
    }
    return input?.toString() ?: ""
}

private fun jsonString(input: JsonElement?, key: String): String? {
    if (input is JsonObject) {
        val value = input[key]
        if (value is JsonPrimitive && value.isString) return value.content
    }
    return null
}

internal fun permissionBody(toolName: String, input: JsonElement?): String = when (toolName) {
    "Permissions" -> {
        val permissions = (input as? JsonObject)?.get("permissions") as? JsonObject
        val fs = permissions?.get("fileSystem") as? JsonObject
        val network = permissions?.get("network") as? JsonObject
        buildString {
            jsonString(input, "reason")?.let { append("原因：$it\n\n") }
            append("网络：${network?.get("enabled") ?: "未申请"}\n")
            append("读取路径：${fs?.get("read") ?: "未申请"}\n")
            append("写入路径：${fs?.get("write") ?: "未申请"}")
        }
    }
    "Bash", "TerminalInput" -> commandText(input)
    "Write" -> {
        val path = jsonString(input, "file_path") ?: jsonString(input, "path") ?: "(unknown path)"
        val content = jsonString(input, "content") ?: input?.toString().orEmpty()
        "$path\n\n$content"
    }
    "Edit", "MultiEdit" -> {
        val changes = (input as? JsonObject)?.get("changes") as? JsonArray
        if (changes != null && changes.isNotEmpty()) {
            changes.joinToString("\n\n") { change ->
                val path = jsonString(change, "path") ?: "(unknown path)"
                val kind = (change as? JsonObject)?.get("kind")
                val operation = jsonString(kind, "type") ?: kind?.toString().orEmpty()
                val movePath = jsonString(kind, "move_path") ?: jsonString(kind, "movePath")
                val destination = movePath?.let { " → $it" }.orEmpty()
                val diff = jsonString(change, "diff") ?: change.toString()
                "$path$destination [$operation]\n$diff"
            }
        } else if ((input as? JsonObject)?.containsKey("changes") == true) {
            // Keep the reason and requested root visible if the provider did
            // not send a preceding fileChange item.
            input.toString()
        } else {
            val path = jsonString(input, "file_path") ?: jsonString(input, "path") ?: "(unknown path)"
            val oldText = jsonString(input, "old_string") ?: "(old content unavailable)"
            val newText = jsonString(input, "new_string") ?: "(new content unavailable)"
            "$path\n\n--- old\n$oldText\n+++ new\n$newText"
        }
    }
    else -> input?.toString() ?: ""
}
