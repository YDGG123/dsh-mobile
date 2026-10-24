package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidChatCacheHydrationTest {
    @Test
    fun cachedTailDoesNotPreventShorterAuthoritativeNetworkHistory() {
        val projection = AndroidGatewayProjection()
        projection.selectSession("s")
        projection.hydrateCachedWindow("s", cached())
        assertEquals("disk", projection.snapshot().conversation.single().text)
        assertNull(projection.cachedWindowJson("s"))
        val history = """{
            "kind":"history","sessionId":"s","historyFormatVersion":1,"hasMore":false,
            "events":[{"type":"assistant/message","seq":1,"time":1,"data":{"message":{"content":[{"type":"text","text":"server"}]}}}]
        }"""
        projection.acceptFrame(history, GatewayWireDecoder.decode(history), "s")
        assertEquals("server", projection.snapshot().conversation.single().text)
        assertNull(projection.snapshot().lastError)
        assertNotNull(projection.cachedWindowJson("s"))
        projection.close()
    }

    @Test
    fun lateDiskNeverOverwritesSnapshotAndFormatMismatchInvalidatesDisk() {
        val projection = AndroidGatewayProjection()
        val invalidated = mutableListOf<String>()
        projection.onCacheInvalidated = invalidated::add
        projection.selectSession("s")
        projection.frame(HISTORY_HELLO)
        projection.hydrateCachedWindow("s", cached())
        assertEquals(listOf("s"), invalidated)
        assertEquals(emptyList<Any>(), projection.snapshot().conversation)
        projection.frame(HISTORY_SUBSCRIBED)
        projection.frame(HISTORY_SNAPSHOT)
        projection.hydrateCachedWindow("s", cached())
        assertEquals("最新历史", projection.snapshot().conversation.single().text)
        projection.close()
    }

    @Test
    fun visitingManyCachedSessionsBoundsTypedProjectionAndKeepsCurrent() {
        val projection = AndroidGatewayProjection()
        repeat(20) { index ->
            val id = "cached-$index"
            projection.selectSession(id)
            projection.hydrateCachedWindow(id, cached().replace("\"s\"", "\"$id\""))
            projection.historyCancelled(id)
            projection.trimInactive()
            org.junit.Assert.assertTrue(projection.cacheSessionIds().size <= 6)
            assertEquals("disk", projection.snapshot().conversation.single().text)
        }
        org.junit.Assert.assertFalse(projection.cacheSessionIds().contains("cached-0"))
        projection.close()
    }

    private fun cached() =
        """{"schema":1,"sessionId":"s","historyFormatVersion":1,"persistentCursor":999,"hasMore":false,"events":[{"sessionId":"s","seq":100,"time":100,"event":{"type":"assistant/message","text":"disk"}}]}"""
}
