package com.airemote.airemote.model.session

/** 会话列表按工作目录（cwd）分组的结果（docs/ui_design.md §6.2）。 */
data class WorkspaceGroup(
    val cwd: String,
    val sessions: List<SessionDto>,
)

/** 把会话按 cwd 分组：组内按 lastActiveAt 倒序，组间按「组内最近活跃」倒序。 */
fun groupByCwd(sessions: List<SessionDto>): List<WorkspaceGroup> =
    sessions
        .groupBy { it.cwd.ifBlank { "默认工作目录" } }
        .map { (cwd, list) -> WorkspaceGroup(cwd, list.sortedByDescending { it.lastActiveAt }) }
        .sortedByDescending { group -> group.sessions.maxOf { it.lastActiveAt } }
