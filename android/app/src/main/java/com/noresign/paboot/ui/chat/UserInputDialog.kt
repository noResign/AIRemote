package com.noresign.paboot.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.noresign.paboot.network.daemon.dto.NormalizedEvent
import kotlinx.serialization.json.*

/** Active provider questions are answered on their original run, never by sending a new turn. */
@Composable
internal fun UserInputDialog(
    request: NormalizedEvent.PermissionRequest,
    submitting: Boolean,
    /** `bad_response` from the daemon: its validation is stricter than the local one. */
    inputError: String?,
    onDecide: (String, String?, JsonElement?) -> Unit,
) {
    val input = request.toolInput as? JsonObject ?: return
    val kind = input.text("kind")
    val questions = (input["questions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val schema = input["requestedSchema"] as? JsonObject
    val fields = schema?.get("properties") as? JsonObject
    val required = (schema?.get("required") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    var values by remember(request.permissionId) { mutableStateOf(emptyMap<String, String>()) }
    var error by remember(request.permissionId) { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("需要你的回答", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    input.text("serverName")?.let { Text("服务：$it") }
                    input.text("message")?.let { Text(it) }
                    if (kind == "questions") questions.forEach { q ->
                        val id = q.text("id") ?: return@forEach
                        Text(q.text("question").orEmpty())
                        (q["options"] as? JsonArray).orEmpty().forEach { option ->
                            val obj = option as? JsonObject ?: return@forEach
                            val label = obj.text("label") ?: return@forEach
                            OutlinedButton(onClick = { values = values + (id to label) }, enabled = !submitting) {
                                Text(listOfNotNull(label, obj.text("description")).joinToString("："))
                            }
                        }
                        OutlinedTextField(
                            value = values[id].orEmpty(), onValueChange = { values = values + (id to it) },
                            label = { Text("回答（可自行输入）") }, enabled = !submitting,
                            visualTransformation = if (q["isSecret"] == JsonPrimitive(true)) PasswordVisualTransformation() else VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (kind == "form") fields?.forEach { (key, raw) ->
                        val field = raw as? JsonObject ?: return@forEach
                        val type = field.text("type")
                        Text((field.text("title") ?: key) + if (key in required) " *" else "（可选）")
                        field.text("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val choices = field["enum"] ?: field["oneOf"] ?: (field["items"] as? JsonObject)?.let { it["enum"] ?: it["anyOf"] }
                        choices?.let { Text("可选值：$it", style = MaterialTheme.typography.bodySmall) }
                        OutlinedTextField(
                            value = values[key].orEmpty(), onValueChange = { values = values + (key to it) },
                            label = { Text(when (type) { "boolean" -> "true 或 false"; "array" -> "JSON 数组，如 [\"A\",\"B\"]"; "number", "integer" -> "数字"; else -> "填写内容" }) },
                            enabled = !submitting, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (kind == "url") {
                        val url = input.text("url").orEmpty()
                        Text(url)
                        OutlinedButton(onClick = {
                            runCatching { uriHandler.openUri(url) }.onFailure { error = "无法打开链接" }
                        }, enabled = url.startsWith("https://") || url.startsWith("http://")) { Text("打开链接") }
                        Text("请在浏览器完成操作后再确认；打开链接不会自动批准。")
                    }
                    if (kind == "unsupported") Text("手机端暂不支持这种验证方式，请拒绝并改用受支持的方式。")
                }
                Text("答案会发送给当前 Agent / 服务，不写入聊天历史。未处理将超时拒绝。", style = MaterialTheme.typography.bodySmall)
                // 本地校验先说，其次才是服务端回来的（本地查不到的枚举/长度/类型）。
                (error ?: inputError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDecide("deny", "用户取消输入", null) }, enabled = !submitting) { Text("拒绝") }
                    Button(enabled = !submitting && kind in listOf("questions", "form", "url"), onClick = {
                        try {
                            val response = when (kind) {
                                "questions" -> buildJsonObject {
                                    put("answers", buildJsonObject {
                                        questions.forEach { q ->
                                            val id = requireNotNull(q.text("id"))
                                            val value = values[id].orEmpty()
                                            require(value.isNotBlank()) { "请回答所有问题" }
                                            put(id, buildJsonObject { put("answers", JsonArray(listOf(JsonPrimitive(value)))) })
                                        }
                                    })
                                }
                                "form" -> buildJsonObject {
                                    fields?.forEach { (key, raw) ->
                                        val value = values[key].orEmpty()
                                        require(key !in required || value.isNotBlank()) { "请填写 $key" }
                                        if (value.isNotBlank()) {
                                            val type = (raw as? JsonObject)?.text("type")
                                            put(key, if (type == "string") JsonPrimitive(value) else Json.parseToJsonElement(value))
                                        }
                                    }
                                }
                                else -> JsonNull
                            }
                            error = null
                            onDecide("allow", null, response)
                        } catch (e: Exception) {
                            error = e.message ?: "请检查输入格式"
                        }
                    }) { Text(if (submitting) "提交中…" else "提交回答") }
                }
            }
        }
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
