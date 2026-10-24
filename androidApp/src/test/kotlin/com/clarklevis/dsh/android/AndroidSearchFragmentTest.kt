package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSearchFragmentTest {
    @Test fun olderSearchIsReadOnlyAndLiveUpdatesRemainInNormalLane() {
        val projection = authoritativeTail()
        val normalBefore = projection.cachedWindowJson("s")
        assertTrue(projection.showSearchFragment("s", 12, cachedPage()))
        assertEquals("cached 12", projection.snapshot().conversation.last().text)
        assertFalse(projection.snapshot().selectedHistoryHasMore)
        assertEquals(normalBefore, projection.cachedWindowJson("s"))
        projection.frame(
            """{"kind":"event","sessionId":"s","subscriptionId":"sub","streamId":"stream-200",
                    "seq":201,"time":201,"event":{"type":"user/message","text":"live 201"}}"""
        )
        assertNull(projection.dropObsoleteSearchFragment())
        assertEquals("cached 12", projection.snapshot().conversation.last().text)
        assertTrue(projection.cachedWindowJson("s")!!.contains("live 201"))
        assertEquals("live 201", projection.closeSearchFragment().conversation.last().text)
        assertEquals(81, projection.snapshot().conversation.size)
        projection.close()
    }

    @Test fun completeEmptyAuthorityRejectsStaleCachedHitAndExpiresOpenFragment() {
        val projection = authoritativeTail()
        assertTrue(projection.showSearchFragment("s", 12, cachedPage()))
        projection.frame(snapshot(emptyList(), hasMore = false, cursor = 220))
        assertEquals(AndroidGatewayProjection.SearchFragmentDisposition.EXPIRED, projection.dropObsoleteSearchFragment())
        assertTrue(projection.snapshot().conversation.isEmpty())
        assertFalse(projection.showSearchFragment("s", 12, cachedPage()))
        projection.close()
    }

    @Test fun coveredButMissingSequenceCannotResurrectDeletedCachedMessage() {
        val projection = authoritativeTail((121..200).filter { it != 150 })
        assertFalse(projection.showSearchFragment("s", 150, cachedPage(sequence = 150)))
        assertEquals("network 200", projection.snapshot().conversation.last().text)
        assertFalse(projection.showSearchFragment("s", 12, cachedPage(sequence = 11)))
        projection.close()
    }

    @Test fun freshAuthorityContainingTargetReplacesFragmentAndSessionChangeDropsIt() {
        val projection = authoritativeTail()
        assertTrue(projection.showSearchFragment("s", 12, cachedPage()))
        projection.frame(snapshot(listOf(12), hasMore = false, cursor = 220))
        assertEquals(AndroidGatewayProjection.SearchFragmentDisposition.REPLACED, projection.dropObsoleteSearchFragment())
        assertEquals("network 12", projection.snapshot().conversation.single().text)
        projection.selectSession("other")
        assertTrue(projection.snapshot().conversation.isEmpty())
        projection.close()
    }

    @Test fun selectingSearchInsideCurrentSessionDoesNotResetLiveSubscription() {
        val projection = authoritativeTail()
        val actor = AndroidProjectionActor(projection, kotlinx.coroutines.Dispatchers.Unconfined, publish = { _, _ -> })
        try {
            kotlinx.coroutines.runBlocking {
                actor.selectSession("s", targetSequence = 12)
                val live = """{"kind":"event","sessionId":"s","subscriptionId":"sub","streamId":"stream-200",
                    "seq":201,"time":201,"event":{"type":"user/message","text":"live 201"}}"""
                actor.acceptFrame(live, com.clarklevis.dsh.shared.protocol.GatewayWireDecoder.decode(live), "s")
            }
            assertEquals("live 201", projection.snapshot().conversation.last().text)
            assertEquals(81, projection.snapshot().conversation.size)
        } finally {
            actor.close()
        }
    }

    @Test fun completeNonemptyAuthorityExpiresOldSearchFragment() {
        val projection = authoritativeTail()
        assertTrue(projection.showSearchFragment("s", 12, cachedPage()))
        projection.frame(snapshot((121..200).toList(), hasMore = false, cursor = 220))
        assertEquals(AndroidGatewayProjection.SearchFragmentDisposition.EXPIRED, projection.dropObsoleteSearchFragment())
        assertFalse(projection.showSearchFragment("s", 12, cachedPage()))
        assertEquals("network 200", projection.snapshot().conversation.last().text)
        projection.close()
    }

    private fun authoritativeTail(sequences: List<Int> = (121..200).toList()): AndroidGatewayProjection = AndroidGatewayProjection().also {
        it.frame(HISTORY_HELLO)
        it.selectSession("s")
        it.frame(HISTORY_SUBSCRIBED)
        it.frame(snapshot(sequences, hasMore = true, cursor = 200))
        assertEquals(sequences.size, it.snapshot().conversation.size)
    }

    private fun cachedPage(sequence: Int = 12) = CachedHistoryWindow(
        sessionId = "s",
        historyFormatVersion = 3,
        events = listOf(SessionEvent("s", sequence, sequence.toDouble(), GatewayEvent("user/message", text = "cached $sequence")))
    )

    private fun snapshot(sequences: List<Int>, hasMore: Boolean, cursor: Int): String {
        val records = sequences.joinToString(",") { seq ->
            """{"type":"user/message","seq":$seq,"time":$seq,"data":{"content":[{"type":"text","text":"network $seq"}]}}"""
        }
        return """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream-$cursor",
            "historyFormatVersion":3,"cursor":$cursor,"events":[$records],"hasMore":$hasMore,"nextBeforeSeq":121}"""
    }
}
