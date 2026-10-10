package com.noresign.paboot.util

import com.noresign.paboot.network.http.NetworkResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * daemon 错误码 → 展示文案。
 *
 * 重点在 [friendlyMessage]：`/api/chat` 的前置校验（runtime 未安装、会话不存在…）只在
 * 非 2xx 正文里给出 code，以前聊天页会把正文丢掉、只显示 "HTTP 503"。
 */
class ApiErrorsTest {

    @Test
    fun `runtime_unavailable 说清是 Agent 没装，而不是一个 HTTP 状态码`() {
        assertEquals(
            "该 Agent 未安装或不在 PATH 上，请在电脑端确认",
            friendlyMessage("runtime_unavailable", 503, "runtime not available: codex"),
        )
    }

    @Test
    fun `会话类错误码有精确文案`() {
        assertEquals("会话不存在或已被删除", friendlyMessage("session_not_found", 404, "session not found"))
        assertEquals(
            "该会话属于另一个 Agent，不能换 Agent 继续",
            friendlyMessage("runtime_mismatch", 400, "runtime does not match session"),
        )
        assertEquals("未知的 Agent 类型", friendlyMessage("unknown_runtime", 400, "unknown runtime: foo"))
        assertEquals("会话目录不在当前工作区内", friendlyMessage("cwd_not_allowed", 400, "session cwd not allowed: /x"))
    }

    @Test
    fun `bad_response 保留服务端指出的字段细节`() {
        // 服务端是唯一会校验枚举/长度/类型的地方，那句细节就是用户唯一的线索。
        assertEquals(
            "回答不符合要求：Invalid option: theme",
            friendlyMessage("bad_response", 400, "Invalid option: theme"),
        )
        assertEquals("该请求已经处理过了", friendlyMessage("permission_resolved", 409, "request already resolved"))
    }

    @Test
    fun `未命中错误码时退回 HTTP 状态码与原文`() {
        assertEquals("token 无效或未授权（401）", friendlyMessage(null, 401, "nope"))
        assertEquals("无法连接 daemon，请检查网络与地址", friendlyMessage(null, -1, "network down"))
        assertEquals("请求失败：HTTP 500", friendlyMessage(null, 500, "HTTP 500"))
    }

    @Test
    fun `friendlyError 与 friendlyMessage 共用同一张表`() {
        val error = NetworkResult.Error(
            code = 503,
            message = "runtime not available: codex",
            apiCode = "runtime_unavailable",
        )
        assertEquals(
            friendlyMessage("runtime_unavailable", 503, "runtime not available: codex"),
            friendlyError(error),
        )
    }
}
