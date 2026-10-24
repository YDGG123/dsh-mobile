package com.clarklevis.dsh.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidRemoteSearchTest {
    @Test fun untaggedResponsesAreSingleFlightAndCannotPolluteNewQuery() {
        val search = AndroidRemoteSearch()
        assertEquals("A", search.request("A"))
        assertNull(search.request("B"))
        val old = search.response(null)
        assertNull(old.acceptedQuery)
        assertEquals("B", old.nextQuery)
        assertEquals("B", search.response(null).acceptedQuery)
    }

    @Test fun unexpectedEchoDoesNotConsumeCurrentRequest() {
        val search = AndroidRemoteSearch()
        search.request("B")
        assertNull(search.response("A").acceptedQuery)
        assertEquals("B", search.response("B").acceptedQuery)
    }

    @Test fun disconnectRejectsLateResponseWithoutConsumingNextConnectionQuery() {
        val search = AndroidRemoteSearch()
        search.request("cached needle")
        search.disconnected()
        assertEquals(false, search.response(null).matched)
        assertNull(search.response("cached needle").acceptedQuery)
        assertEquals("new query", search.request("new query"))
        assertEquals("new query", search.response("new query").acceptedQuery)
    }

    @Test fun timeoutBlocksUntaggedUntilReconnectAndBlankCancelsDisplay() {
        val search = AndroidRemoteSearch()
        search.request("A")
        search.timeout("A")
        assertNull(search.request("B"))
        assertNull(search.response(null).acceptedQuery)
        search.disconnected()
        assertEquals("B", search.request("B"))
        search.request("")
        assertNull(search.response(null).acceptedQuery)
    }
}
