package com.noresign.paboot.model.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** TodoWrite 工具里的一条任务。 */
data class TodoItem(
    val content: String,
    val status: String,
)

/** 解析 TodoWrite 工具的 input（`{ "todos": [...] }`）为任务列表。 */
fun parseTodos(input: JsonElement?): List<TodoItem> {
    val obj = input as? JsonObject ?: return emptyList()
    val arr = obj["todos"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val item = el as? JsonObject ?: return@mapNotNull null
        val content = (item["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return@mapNotNull null
        val status = (item["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "pending"
        TodoItem(content = content, status = status)
    }
}
