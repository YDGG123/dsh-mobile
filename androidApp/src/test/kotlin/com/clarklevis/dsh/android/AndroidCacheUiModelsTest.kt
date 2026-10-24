package com.clarklevis.dsh.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidCacheUiModelsTest {
    @Test fun sharedSearchResponseDecodesWithoutAndroidSerializationCompilerPlugin() {
        val result = AndroidLocalChatMatches.decode(
            """{"coverage":"cached-only","hasMore":true,"results":[{"sessionId":"s","seq":12,"time":1.5,"snippet":"本地命中"}]}"""
        )
        assertEquals(true, result.hasMore)
        assertEquals(AndroidLocalChatMatch("s", 12, 1.5, "本地命中"), result.results.single())
    }

    @Test fun sharedStatisticsAcceptsAdditionalFields() {
        val stats = AndroidChatCacheMemoryStatistics.decode(
            """{"logicalBytes":123,"sessionCount":2,"memoryBytes":100,"memorySessions":2,"memoryHits":3,"diskHits":4,"misses":5,"epoch":6}"""
        )
        assertEquals(AndroidChatCacheMemoryStatistics(100, 2, 3, 4, 5), stats)
    }
}
