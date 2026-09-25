package com.airemote.airemote.viewmodel

/**
 * 聊天流断线后的重连策略。**纯逻辑、无 Android 依赖**，便于单测。
 *
 * 一轮（一次 `runStream` 的 collect）结束后，[decide] 给出下一步：收口、放弃、还是退避重连。
 * 状态的累积（连续失败轮数、连不上 daemon 的时长）由调用方维护，这里只管判定。
 *
 * 设计前提是 daemon 的 run 与连接解耦：客户端断开后 run 继续跑，所以「流断了」几乎总是
 * 可以重连的——唯一的例外是 run 真的没了。
 */
internal object ReconnectPolicy {

    /** 一轮流结束后的去向。 */
    sealed interface Decision {
        /** 收到终局事件，run 正常结束。 */
        data object Finished : Decision

        /** run 已不在 daemon 上（结束 / daemon 重启 / 被看门狗取消），收口。 */
        data object Settled : Decision

        /** 重试没有意义（鉴权失败、run 不存在等）。 */
        data object GiveUp : Decision

        /** 退避 [delayMs] 毫秒后重连。 */
        data class Retry(val delayMs: Long) : Decision
    }

    /** 退避表（毫秒），最后一档是封顶值。 */
    private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

    /**
     * 一轮连接活过这个时长（或收到过帧），就认为它是"健康的"，退避归零。
     * 否则刚连上就被掐断会让退避迅速拉满，反而拖慢恢复。
     */
    const val HEALTHY_ATTEMPT_MS = 5_000L

    /**
     * 连续探测不到 daemon 的容忍时长。超过就放弃——此时我们对 run 的状态一无所知，
     * 继续重试只是在后台空转（笔记本休眠是典型场景）。
     *
     * 注意这只约束「daemon 不可达」；daemon 可达且明确报告 run 仍在运行时（网络抖动、
     * 中间设备掐长连接），重试不受此预算限制，一直试到 run 消失为止。
     */
    const val UNREACHABLE_BUDGET_MS = 60_000L

    /** 这些状态码重试无意义：请求本身有问题，不是链路问题。 */
    fun isNonRetryable(httpCode: Int?): Boolean =
        httpCode == 400 || httpCode == 401 || httpCode == 403 || httpCode == 404

    /**
     * 第 [attempt] 轮失败后的退避时长（[attempt] 从 0 开始，超出表长则封顶）。
     * [jitter] 取值 `[0, 1)`，把延迟抖动到 0.5~1.5 倍，避免多客户端同时重连。
     */
    fun backoffMs(attempt: Int, jitter: Double): Long {
        val base = BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.lastIndex)]
        return (base * (0.5 + jitter.coerceIn(0.0, 1.0))).toLong()
    }

    /**
     * 判定一轮流结束后的去向。
     *
     * @param terminal 本轮是否收到终局事件
     * @param httpCode 本轮 `Failed` 的 HTTP 状态码；连接层失败为 null
     * @param runActive `GET /api/runs` 的探测结果；null = 探测本身失败（daemon 不可达）
     * @param unreachableMs 连续探测失败的累计时长
     * @param attempt 已连续失败的轮数
     */
    fun decide(
        terminal: Boolean,
        httpCode: Int?,
        runActive: Boolean?,
        unreachableMs: Long,
        attempt: Int,
        jitter: Double,
    ): Decision {
        if (terminal) return Decision.Finished
        if (isNonRetryable(httpCode)) return Decision.GiveUp
        if (runActive == false) return Decision.Settled
        // runActive == null：探测不到 daemon。给它一个预算，避免在后台无限空转。
        if (runActive == null && unreachableMs >= UNREACHABLE_BUDGET_MS) return Decision.GiveUp
        return Decision.Retry(backoffMs(attempt, jitter))
    }
}
