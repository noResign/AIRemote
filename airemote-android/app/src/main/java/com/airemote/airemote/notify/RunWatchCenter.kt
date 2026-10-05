package com.airemote.airemote.notify

import android.content.Context
import android.os.SystemClock
import com.airemote.airemote.data.local.SettingsStore
import com.airemote.airemote.data.repository.ChatRepository
import com.airemote.airemote.util.ReconnectPolicy
import com.airemote.network.airemote.ChatStreamEvent
import com.airemote.network.airemote.dto.NormalizedEvent
import com.airemote.network.airemote.dto.SseFrame
import com.airemote.network.http.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 后台任务监听：把「客户端发起、还没结束」的 run 挂到一条**独立的** SSE 连接上，
 * 在对应会话不可见时把「需要审批」「任务结束」转成系统通知。
 *
 * 为什么需要它：聊天页的流是屏幕级 ViewModel 持有的，退出会话页（或锁屏后进程被回收）
 * 就断了，而 daemon 的 run 还在跑——审批还会超时自动拒绝。这里补上「用户不在看的时候
 * 谁来收事件」。
 *
 * 与聊天页的关系：daemon 允许同一 run 有多个订阅者，聊天页照旧收自己的；这里只负责提醒，
 * 不参与任何 UI 状态。注册入口只有 [watch] 一个，后期接入定时任务时同样调它。
 */
object RunWatchCenter {

    /** 同时监听的 run 上限，防止异常情况下无限堆积。 */
    private const val MAX_WATCHED = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 已提醒过的审批 id：重连回放 / 进程重启后会重复收到同一个请求。 */
    private val notifiedPermissions = ConcurrentHashMap.newKeySet<String>()

    private val repository = ChatRepository()

    private var appContext: Context? = null

    private val _watched = MutableStateFlow<List<WatchedRun>>(emptyList())

    /** 当前正在后台监听的 run（设置页与常驻通知用它显示状态）。 */
    val watched = _watched.asStateFlow()

    /** 应用启动时调用：恢复上次没跑完的监听。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!SettingsStore.backgroundNotifications) return
        if (_watched.value.isNotEmpty()) return
        val restored = RunWatchStore.load().takeLast(MAX_WATCHED)
        if (restored.isEmpty()) return
        _watched.value = restored
        restored.forEach(::launchWatch)
        RunWatchService.start(context)
    }

    /**
     * 登记一个待监听 run。重复调用只更新标题；runId 或 sessionId 缺失时忽略
     * （新会话的首帧只带 runId，等 sessionId 从 `status` 帧里补上再登记）。
     */
    fun watch(runId: String, sessionId: String?, title: String?) {
        if (!SettingsStore.backgroundNotifications) return
        if (runId.isBlank() || sessionId.isNullOrBlank()) return
        val normalizedTitle = title?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        // 读-改-写要走 update 的 CAS：watch 在主线程、settle 在 IO 线程，
        // 多 run 并发收口时直接 `.value = ...` 会丢更新。
        var changed: WatchedRun? = null
        var evicted: String? = null
        _watched.update { current ->
            // update 可能因并发重试重跑闭包，先清掉上一轮的捕获值
            changed = null
            evicted = null
            val existing = current.firstOrNull { it.runId == runId }
            if (existing != null && existing.sessionId == sessionId && existing.title == normalizedTitle) {
                return@update current
            }
            val entry = (existing ?: WatchedRun(runId, sessionId, startedAt = System.currentTimeMillis()))
                .copy(sessionId = sessionId, title = normalizedTitle)
            changed = entry
            val next = current.filterNot { it.runId == runId } + entry
            if (next.size <= MAX_WATCHED) {
                next
            } else {
                // 超上限，挤掉最老的（takeLast 保留最新）。它的 job 要一并取消：否则监听还在跑，
                // 却已不在 _watched / 持久化里，服务计数与重启恢复都对不上。
                val kept = next.takeLast(MAX_WATCHED)
                evicted = next.firstOrNull { cand -> kept.none { it.runId == cand.runId } }?.runId
                kept
            }
        }
        val entry = changed ?: return
        evicted?.let { jobs.remove(it)?.cancel() }
        RunWatchStore.save(_watched.value)
        launchWatch(entry)
        appContext?.let { RunWatchService.start(it) }
    }

    /** 用户进会话时清掉它的通知；监听本身继续，完成时人已经在页面里就不会再弹。 */
    fun clearFor(sessionId: String) {
        appContext?.let { RunNotifications.clear(it, sessionId) }
    }

    /** 开关关闭 / 连接切换时清空：停掉所有监听与通知，并清掉持久化。 */
    fun clearAll() {
        // 先摘掉登记再取消：被取消的 job 在 finally 里调 settle，身份校验失败即直接返回，
        // 不会回头改 _watched 或补发结果通知。
        val running = jobs.values.toList()
        jobs.clear()
        running.forEach { it.cancel() }
        notifiedPermissions.clear()
        _watched.value = emptyList()
        RunWatchStore.save(emptyList())
        appContext?.let {
            // 残留通知也要清：换 daemon 后它的深链指向旧 daemon 的会话，点开只会报错
            RunNotifications.clearAll(it)
            RunWatchService.stop(it)
        }
    }

    /** 设置页的「后台任务提醒」开关。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        SettingsStore.backgroundNotifications = enabled
        if (enabled) {
            init(context)
        } else {
            clearAll()
        }
    }

    private fun launchWatch(run: WatchedRun) {
        jobs.remove(run.runId)?.cancel()
        // LAZY 起手：先握住 Job 再 start，保证 finally 里的 settle 拿到的一定是自己这个 job，
        // 否则被取代的旧 job 会在收口时把新 job 从 jobs / _watched 里误删。
        val holder = arrayOfNulls<Job>(1)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var outcome: Outcome? = null
            try {
                outcome = follow(run)
            } finally {
                settle(run, holder[0], outcome)
            }
        }
        holder[0] = job
        jobs[run.runId] = job
        job.start()
    }

    /**
     * 跟着一条 run 的流走，直到终局事件或 run 从 daemon 消失。
     * 断线沿用聊天页那套策略（`ReconnectPolicy`）：run 还在就退避重连，已经不在 daemon
     * 内存里就补回尾部事件再收口，**daemon 持续不可达则超预算放弃**——否则这里会空转，
     * 而它背后还挂着 [RunWatchService]，那条常驻通知就永远撤不掉。
     */
    private suspend fun follow(run: WatchedRun): Outcome? {
        var after: Long? = null
        var attempt = 0
        var unreachableSince: Long? = null
        while (currentCoroutineContext().isActive) {
            var terminal: Outcome? = null
            var frames = 0
            var failure: ChatStreamEvent.Failed? = null
            val startedAt = SystemClock.elapsedRealtime()

            repository.runStream(run.runId, after).collect { evt ->
                when (evt) {
                    is ChatStreamEvent.Frame -> {
                        frames++
                        if (evt.frame.seq > (after ?: -1L)) after = evt.frame.seq
                        handleFrame(run, evt.frame)?.let { terminal = it }
                    }
                    is ChatStreamEvent.Failed -> failure = evt
                    ChatStreamEvent.Closed -> Unit
                }
            }

            // 已终局就不必探测了；否则看 run 是否还在 daemon 内存里。null = 探测本身失败
            // （daemon 不可达），累加不可达时长，交给 decide 判断何时放弃。
            val probed = if (terminal != null) null else repository.isRunActive(run.runId)
            val now = SystemClock.elapsedRealtime()
            if (probed == null) {
                if (unreachableSince == null) unreachableSince = now
            } else {
                unreachableSince = null
            }

            val decision = ReconnectPolicy.decide(
                terminal = terminal != null,
                httpCode = failure?.httpCode,
                runActive = probed,
                unreachableMs = unreachableSince?.let { now - it } ?: 0L,
                attempt = attempt,
                jitter = Random.nextDouble(),
            )
            when (decision) {
                ReconnectPolicy.Decision.Finished -> return terminal
                // run 已从 daemon 消失（结束 / 重启 / 被看门狗取消）：补回尾部事件再收口
                ReconnectPolicy.Decision.Settled -> return catchUp(run, after)
                ReconnectPolicy.Decision.GiveUp -> return null
                is ReconnectPolicy.Decision.Retry -> {
                    val streamMs = now - startedAt
                    attempt = if (frames > 0 || streamMs > ReconnectPolicy.HEALTHY_ATTEMPT_MS) {
                        0
                    } else {
                        attempt + 1
                    }
                    delay(decision.delayMs)
                }
            }
        }
        return null
    }

    /** 一次性补齐 `after` 之后的事件（run 已消失时用），返回其中的终局结果。 */
    private suspend fun catchUp(run: WatchedRun, after: Long?): Outcome? {
        if (after == null) return null
        return when (val r = repository.runEvents(run.runId, after)) {
            is NetworkResult.Success -> {
                var outcome: Outcome? = null
                for (frame in r.data.events) {
                    handleFrame(run, frame)?.let { outcome = it }
                }
                outcome
            }
            is NetworkResult.Error -> null
        }
    }

    /** 处理一帧；返回非 null 表示这是终局事件。 */
    private fun handleFrame(run: WatchedRun, frame: SseFrame): Outcome? = when (val e = frame.event) {
        is NormalizedEvent.PermissionRequest -> {
            onPermissionEvent(run, e)
            null
        }
        is NormalizedEvent.Status -> if (e.terminal == true) Outcome(e.label) else null
        is NormalizedEvent.Error -> if (e.terminal == true) Outcome("failed", e.message) else null
        else -> null
    }

    /**
     * 一条审批事件：pending 就提醒（若人没在看），已决就把之前那条提醒撤掉。
     *
     * 已决帧（allowed / denied / timed_out）会在重连回放里原样出现；daemon 侧超时自动拒绝
     * （默认 120s）也走这一帧。不撤的话，那条高优先级「需要审批」会一直挂在通知栏里，
     * 点开却没有什么可审批的。
     */
    private fun onPermissionEvent(run: WatchedRun, e: NormalizedEvent.PermissionRequest) {
        val context = appContext ?: return
        if (e.status != null && e.status != "pending") {
            RunNotifications.clear(context, run.sessionId)
            return
        }
        // 去重集合只增不减（一个长会话能攒下很多次审批），超量时整体清空即可：
        // 最坏情况是清理后重连回放再提醒一次，代价远低于无界增长。
        if (notifiedPermissions.size > 500) notifiedPermissions.clear()
        if (!notifiedPermissions.add(e.permissionId)) return
        if (ChatVisibility.isViewing(run.sessionId)) return
        RunNotifications.postApproval(context, run.sessionId, run.title, e.toolName, toolDetail(e))
    }

    /**
     * 收口：注销监听、落盘、必要时补一条结果通知。
     *
     * [job] 是本次监听的自身引用：若它已不是该 run 当前登记的 job（被新监听取代，或已被
     * [clearAll] 摘除），就什么都不做——收口是新的那条监听 / clearAll 的责任。
     */
    private fun settle(run: WatchedRun, job: Job?, outcome: Outcome?) {
        if (job == null || jobs[run.runId] !== job) return
        jobs.remove(run.runId)
        val remaining = _watched.updateAndGet { list -> list.filterNot { it.runId == run.runId } }
        RunWatchStore.save(remaining)
        val context = appContext
        if (context != null &&
            outcome != null &&
            SettingsStore.backgroundNotifications &&
            !ChatVisibility.isViewing(run.sessionId)
        ) {
            RunNotifications.postResult(context, run.sessionId, run.title, outcome.label, outcome.message)
        }
        if (context != null && remaining.isEmpty()) RunWatchService.stop(context)
    }

    /** 审批卡上最有用的一行：Bash 命令 / 文件路径 / 搜索词，取不到就退化成工具名。 */
    private fun toolDetail(e: NormalizedEvent.PermissionRequest): String? {
        val obj = e.toolInput as? JsonObject ?: return null
        val key = listOf("command", "file_path", "path", "pattern", "url", "notebook_path")
            .firstOrNull { obj.containsKey(it) }
            ?: obj.keys.firstOrNull()
            ?: return null
        val value = (obj[key] as? JsonPrimitive)?.content ?: return null
        return value.replace('\n', ' ').trim().take(120).takeIf { it.isNotEmpty() }
    }

    /** 终局事件归一化后的结果。 */
    private data class Outcome(val label: String, val message: String? = null)
}
