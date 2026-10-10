package com.noresign.paboot.notify

import com.tencent.mmkv.MMKV
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 待监听 run 的持久化（MMKV，与连接设置同一份存储）。 */
object RunWatchStore {

    private const val KEY = "watched_runs"
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(WatchedRun.serializer())
    private val kv: MMKV by lazy { MMKV.defaultMMKV() }

    fun load(): List<WatchedRun> {
        val raw = kv.decodeString(KEY) ?: return emptyList()
        // 协议升级 / 手动改坏都只当没有：监听列表丢了不影响任务本身
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    fun save(runs: List<WatchedRun>) {
        if (runs.isEmpty()) {
            kv.removeValueForKey(KEY)
            return
        }
        kv.encode(KEY, json.encodeToString(serializer, runs))
    }
}
