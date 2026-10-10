package com.noresign.paboot.notify

import kotlinx.serialization.Serializable

/**
 * 一个「客户端发起了、但还没结束」的 run。
 *
 * 持久化的意义：进程被系统回收后，[RunWatchService] 重启时靠它恢复监听，否则锁屏期间
 * 跑到一半的任务就再也弹不出「完成 / 需要审批」了。
 */
@Serializable
data class WatchedRun(
    val runId: String,
    val sessionId: String,
    /** 通知标题里的会话名；新会话在标题定下来之前先用 prompt 兜底。 */
    val title: String? = null,
    val startedAt: Long = 0,
)
