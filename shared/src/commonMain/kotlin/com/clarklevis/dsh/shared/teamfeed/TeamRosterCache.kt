package com.clarklevis.dsh.shared.teamfeed

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * D1 水合缓存 + resume（段 4）。
 *
 * 对应 iOS：`TeamRosterCacheStore`（启动先水合上次名册**含 glyph**，快照到了再静默替换）
 * 与 `TeamFeedResumeStore`（落盘 `{instanceId, seq}`，重连带 resume 零往返补增量）。
 *
 * 平台存储由注入的 [TeamFeedKeyValueStore] 提供（Android 落 DataStore/文件、桌面侧任意 KV）；
 * 本文件只管**序列化与语义**，全是纯 Kotlin，jvmTest 可直接回放。
 */
interface TeamFeedKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

private val cacheJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

object TeamFeedCacheKeys {
    const val ROSTER = "team-feed-roster-v1"
    const val RESUME = "team-feed-resume-v1"
}

@Serializable
data class TeamRosterCachePayload(
    val instanceId: String? = null,
    val seq: Long = 0,
    val degraded: Boolean = false,
    val mainBotIds: List<String> = emptyList(),
    val rows: List<TeamFeedRow> = emptyList(),
    val cards: List<TeamFeedCard> = emptyList(),
)

@Serializable
data class TeamFeedResumePayload(val instanceId: String?, val seq: Long)

/** 名册水合：保存 / 读取 / 清除。读失败（坏 JSON）返回 null 并清掉坏数据，**不抛**（水合不能把启动搞崩）。 */
class TeamRosterCache(private val store: TeamFeedKeyValueStore) {

    fun save(state: TeamFeedReducer.TeamFeedState) {
        val payload = TeamRosterCachePayload(
            instanceId = state.instanceId,
            seq = state.seq,
            degraded = state.degraded,
            mainBotIds = state.mainBotIds,
            rows = state.rows.values.sortedBy { it.id },
            cards = state.cards.values.sortedBy { it.id },
        )
        runCatching { store.put(TeamFeedCacheKeys.ROSTER, cacheJson.encodeToString(payload)) }
    }

    fun hydrate(): TeamReducerCacheResult? {
        val raw = store.get(TeamFeedCacheKeys.ROSTER) ?: return null
        val payload = runCatching { cacheJson.decodeFromString<TeamRosterCachePayload>(raw) }.getOrNull()
        if (payload == null) {
            // 坏数据不静默留在盘上：下次启动还会撞同一堵墙。
            runCatching { store.remove(TeamFeedCacheKeys.ROSTER) }
            return null
        }
        return TeamReducerCacheResult(
            state = TeamFeedReducer.TeamFeedState(
                instanceId = payload.instanceId,
                seq = payload.seq,
                degraded = payload.degraded,
                mainBotIds = payload.mainBotIds,
                rows = payload.rows.associateBy { it.id },
                cards = payload.cards.associateBy { it.id },
            ),
            degraded = payload.degraded,
        )
    }

    fun clear() {
        runCatching { store.remove(TeamFeedCacheKeys.ROSTER) }
    }
}

data class TeamReducerCacheResult(val state: TeamFeedReducer.TeamFeedState, val degraded: Boolean)

/** resume：`{instanceId, seq}` 只有真的变了才写盘（iOS `:150` 同规，省 UserDefaults 写放大）。 */
class TeamFeedResumeStore(private val store: TeamFeedKeyValueStore) {

    fun load(): TeamFeedResumePayload? {
        val raw = store.get(TeamFeedCacheKeys.RESUME) ?: return null
        return runCatching { cacheJson.decodeFromString<TeamFeedResumePayload>(raw) }.getOrNull()
    }

    /** 返回 true = 本次真的写了盘（调用方可据此打日志）。 */
    fun save(instanceId: String?, seq: Long): Boolean {
        val current = load()
        if (current != null && current.instanceId == instanceId && current.seq == seq) return false
        runCatching { store.put(TeamFeedCacheKeys.RESUME, cacheJson.encodeToString(TeamFeedResumePayload(instanceId, seq))) }
        return true
    }

    fun clear() {
        runCatching { store.remove(TeamFeedCacheKeys.RESUME) }
    }
}

/** 内存实现：单测与预览用；平台实现各自落盘。 */
class InMemoryTeamFeedKeyValueStore : TeamFeedKeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}
