package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.cache.ChatPrefetchCandidate
import com.clarklevis.dsh.shared.cache.ChatPrefetchPolicy
import com.clarklevis.dsh.shared.cache.SessionHeatEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatPrefetchPolicyTest {
    private val now = 2_000_000.0

    @Test
    fun coldStartUsesRemoteActivityAndStableIdentifierTies() {
        val candidates = listOf(candidate("c", now - 100), candidate("b", now), candidate("a", now))
        assertEquals(listOf("a", "b", "c"), ChatPrefetchPolicy.rank(candidates, emptyList(), null, now))
    }

    @Test
    fun excludesSelectedEmptyAndInvalidAndBoundsWorkToSixDistinctSessions() {
        val candidates = (1..12).map { candidate("s$it", now) } +
            listOf(candidate("s1", now), candidate("", now), ChatPrefetchCandidate("empty", hasConversation = false))
        val result = ChatPrefetchPolicy.rank(candidates, emptyList(), "s1", now)
        assertEquals(6, result.size)
        assertEquals(6, result.toSet().size)
        assertTrue(result.none { it in setOf("s1", "", "empty") })
    }

    @Test
    fun trueOpensOutrankMerelyRecentRemoteActivityAndFrequencyMatters() {
        val heat = listOf(SessionHeatEntity("g", "frequent", now, 5.0), SessionHeatEntity("g", "once", now, 1.0))
        val candidates = listOf(candidate("remote", now), candidate("once", now), candidate("frequent", now - 1000))
        assertEquals(listOf("frequent", "once", "remote"), ChatPrefetchPolicy.rank(candidates, heat, null, now))
    }

    @Test
    fun historicalFrequencyDecaysInsteadOfPermanentlyPinningOldSessions() {
        val old = now - 60 * 24 * 60 * 60
        val heat = listOf(SessionHeatEntity("g", "old", old, 64.0), SessionHeatEntity("g", "recent", now, 1.0))
        val result = ChatPrefetchPolicy.rank(listOf(candidate("old", old), candidate("recent", now)), heat, null, now)
        assertEquals("recent", result.first())
        assertEquals(2.0, ChatPrefetchPolicy.decay(4.0, 7.0 * 24 * 60 * 60))
    }

    @Test
    fun runningAndUnreadIncreasePriorityWithoutOpeningTheSession() {
        val candidates = listOf(
            candidate("plain", now),
            candidate("unread", now).copy(hasUnread = true),
            candidate("running", now).copy(isRunning = true)
        )
        assertEquals(listOf("running", "unread", "plain"), ChatPrefetchPolicy.rank(candidates, emptyList(), null, now))
    }

    @Test
    fun futureTimestampCannotExponentiallyInflatePriority() {
        assertEquals(3.0, ChatPrefetchPolicy.decay(3.0, -100_000.0))
        assertEquals(
            listOf("a", "z"),
            ChatPrefetchPolicy.rank(listOf(candidate("z", now), candidate("a", now * 100)), emptyList(), null, now)
        )
    }

    private fun candidate(id: String, time: Double) = ChatPrefetchCandidate(id, lastActivityEpochSeconds = time)
}
