package com.airemote.airemote.util

import com.airemote.network.http.NetworkResult

/**
 * daemon 错误 → 可直接展示的中文文案。
 *
 * 优先按 [NetworkResult.Error.apiCode]（daemon `ApiError.code`）做精确映射，未命中再退回
 * HTTP 状态码 / 网络层错误码。各 ViewModel 统一走这里，不要再各写一份 `when (code)`。
 */
fun friendlyError(error: NetworkResult.Error, fallbackPrefix: String = "请求失败"): String = when (error.apiCode) {
    "workspace_exists" -> "该目录已经是工作区了"
    "workspace_not_empty" -> "该工作区还有会话，请勾选「同时删除会话」后再删"
    "workspace_is_default" -> "默认工作区不能删除，请先把另一个工作区设为默认"
    "workspace_disabled" -> "该工作区已停用"
    "directory_not_found" -> "目录不存在或已被删除"
    "not_a_directory" -> "所选路径不是文件夹"
    "directory_not_accessible" -> "目录无法访问（权限不足）"
    "shortcut_exists" -> "该目录已经在 tab 上了"
    "dir_exists" -> "该目录已在本工作区的附加目录里"
    "primary_dir" -> "该目录已经是工作区主目录了"
    else -> when (error.code) {
        401 -> "token 无效或未授权（401）"
        -1 -> "无法连接 daemon，请检查网络与地址"
        else -> "$fallbackPrefix：${error.message}"
    }
}
