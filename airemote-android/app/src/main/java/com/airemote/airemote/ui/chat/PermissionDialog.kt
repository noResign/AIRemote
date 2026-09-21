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
import com.airemote.network.airemote.dto.NormalizedEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun PermissionDialog(
    permission: NormalizedEvent.PermissionRequest,
    onDecide: (String, String?) -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(permissionTitle(permission.toolName), style = MaterialTheme.typography.titleMedium)
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
                        onClick = { onDecide("deny", "用户拒绝") },
                        modifier = Modifier.weight(1f),
                    ) { Text("拒绝", color = MaterialTheme.colorScheme.error) }
                    // 目录门禁的工具（Read/Grep）不提供「允许全部」：它的语义是「本 Session 内
                    // 读任意路径」——比「允许此目录」大得多，而且不写目录授权，会把整套目录
                    // 记录机制架空。用户在这里要的几乎总是「允许这一个目录」。
                    if (!isDirGatedTool(permission.toolName)) {
                        OutlinedButton(
                            onClick = { onDecide("allow_all", null) },
                            modifier = Modifier.weight(1f),
                        ) { Text("允许全部") }
                    }
                    Button(
                        onClick = { onDecide("allow", null) },
                        modifier = Modifier.weight(1f),
                    ) { Text("允许") }
                }
            }
        }
    }
}

private const val MCP_PREFIX = "mcp__"

/** 目录级门禁的工具：越界读取要远程审批，批准后目录记入工作区。 */
internal fun isDirGatedTool(toolName: String): Boolean = toolName == "Read" || toolName == "Grep"

internal fun isMcpTool(toolName: String): Boolean = toolName.startsWith(MCP_PREFIX)

/** `mcp__github__create_issue` → `github · create_issue`; other tool names unchanged. */
internal fun toolLabel(toolName: String): String {
    if (!isMcpTool(toolName)) return toolName
    return toolName.removePrefix(MCP_PREFIX).replace("__", " · ")
}

internal fun permissionTitle(toolName: String): String =
    if (isDirGatedTool(toolName)) "Claude 请求读取工作区外的内容" else "Claude 请求执行"

/** Warning under the body: what the tool can do, plus what 「允许全部」 actually grants. */
internal fun permissionWarning(toolName: String): String {
    if (isMcpTool(toolName)) {
        return "这是 MCP Server 提供的工具，可能调用外部服务或产生副作用。" +
            "超时未处理将自动拒绝；「允许全部」在本 Session 内不再询问该 Server 的所有工具。"
    }
    if (isDirGatedTool(toolName)) {
        // 作用域必须写明：批准不是只影响当前对话，而是整个工作区。
        // 这张卡片没有「允许全部」，所以文案里也不提它。
        return "此路径不在工作区内。允许后会把该目录加入当前工作区，" +
            "该工作区所有会话（含以后新建的）都不再询问；可在「工作区管理」里撤销。" +
            "超时未处理将自动拒绝。"
    }
    val risk = when (toolName) {
        "Bash" -> "此命令可能修改文件或系统"
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
    "Bash" -> commandText(input)
    "Write" -> {
        val path = jsonString(input, "file_path") ?: jsonString(input, "path") ?: "(unknown path)"
        val content = jsonString(input, "content") ?: input?.toString().orEmpty()
        "$path\n\n$content"
    }
    "Edit", "MultiEdit" -> {
        val path = jsonString(input, "file_path") ?: jsonString(input, "path") ?: "(unknown path)"
        val oldText = jsonString(input, "old_string") ?: "(old content unavailable)"
        val newText = jsonString(input, "new_string") ?: "(new content unavailable)"
        "$path\n\n--- old\n$oldText\n+++ new\n$newText"
    }
    // 读取类的关键信息是「要碰哪个目录」，批准粒度也是目录，所以把路径顶到最前。
    "Read" -> jsonString(input, "file_path") ?: jsonString(input, "path") ?: input?.toString().orEmpty()
    "Grep" -> {
        val pattern = jsonString(input, "pattern") ?: ""
        val dir = jsonString(input, "path") ?: "(当前目录)"
        val glob = jsonString(input, "glob")?.let { "\nglob: $it" } ?: ""
        "$dir\n\npattern: $pattern$glob"
    }
    else -> input?.toString() ?: ""
}
