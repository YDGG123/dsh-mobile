package com.clarklevis.dsh.shared.teamfeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D1 段 4 的判据用例：水合回放 / resume 零往返 / 坏数据不清启动。 */
class TeamRosterCacheTest {

    private fun store() = InMemoryTeamFeedKeyValueStore()

    @Test
    fun saveThenHydrateRoundTrips() {
        val cache = TeamRosterCache(store())
        val state = TeamFeedReducer.TeamFeedState(
            instanceId = "inst-1",
            seq = 42,
            degraded = true,
            mainBotIds = listOf("b1"),
            rows = mapOf(
                "b1" to TeamFeedRow(id = "b1", kind = "bot", name = "iOSCoder", sessionId = "s1", activity = "working"),
                "r1" to TeamFeedRow(id = "r1", kind = "room", name = "群", members = listOf("b1")),
            ),
            cards = mapOf("c1" to TeamFeedCard(id = "c1", owner = "r1", kind = "question", prompt = "在吗")),
        )
        cache.save(state)
        val hydrated = cache.hydrate()
        assertTrue(hydrated != null)
        assertEquals(state.rows.keys, hydrated!!.state.rows.keys)
        assertEquals(42L, hydrated.state.seq)
        assertEquals(hydrated.state.rows.getValue("b1").activity, "working")
        assertTrue(hydrated.state.rows.getValue("b1").glyph == null, "glyph 也要跟着水合（iOS TeamRosterCacheStore 同规）")
        assertTrue(hydrated.degraded)
    }

    @Test
    fun corruptCacheReturnsNullAndClearsItself() {
        val kv = store()
        kv.put(TeamFeedCacheKeys.ROSTER, "{not-json")
        val cache = TeamRosterCache(kv)
        assertNull(cache.hydrate(), "坏数据不该抛，也不该返回半截状态")
        assertNull(kv.get(TeamFeedCacheKeys.ROSTER), "坏数据要顺手清掉，下次启动别再撞")
    }

    @Test
    fun resumeStoreWritesOnlyOnChange() {
        val kv = store()
        val resume = TeamFeedResumeStore(kv)
        assertTrue(resume.save("inst-1", 7), "首次写盘")
        assertEquals(TeamFeedResumePayload("inst-1", 7), resume.load())
        assertFalse(resume.save("inst-1", 7), "seq/instanceId 没变就不写盘（iOS :150 同规）")
        assertTrue(resume.save("inst-1", 8), "seq 前进要写盘")
        assertTrue(resume.save("inst-2", 8), "instanceId 变更要写盘")
        assertEquals(TeamFeedResumePayload("inst-2", 8), resume.load())
    }

}
