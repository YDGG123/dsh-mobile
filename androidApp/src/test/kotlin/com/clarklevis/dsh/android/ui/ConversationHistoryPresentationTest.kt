package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationHistoryPresentationTest {
    private fun message(id: String, kind: ConversationItemKind = ConversationItemKind.USER, text: String = id) =
        ConversationItem(id = id, kind = kind, title = "", text = text, epochSeconds = 1.0)
    private fun entries(vararg items: ConversationItem) = prepareConversationPresentation(items.toList(), false).entries

    @Test fun prependPreservesThePartiallyVisibleMessageRatherThanTheOldIndex() {
        val old = entries(message("a"), message("b"), message("c"))
        val target = entries(message("older"), message("a"), message("b"), message("c"))
        assertEquals(HistoryReadingAnchor("b", 2, -47), historyReadingAnchor(old, target,
            listOf(HistoryVisibleRow("b", -47), HistoryVisibleRow("c", 120))))
    }

    @Test fun mergedGroupHeaderSelectsRetainedBodyWithItsOwnOffset() {
        val first = message("tool-2", ConversationItemKind.REASONING)
        val reply = message("reply")
        val old = entries(first, reply)
        val target = entries(message("tool-1", ConversationItemKind.REASONING), first, reply)
        assertEquals(HistoryReadingAnchor("reply", 1, 80), historyReadingAnchor(old, target,
            listOf(HistoryVisibleRow(old[0].id, -20), HistoryVisibleRow("reply", 80))))
    }

    @Test fun soleVisibleMergedGroupUsesItsSharedEvents() {
        val first = message("tool-2", ConversationItemKind.REASONING)
        val old = entries(first)
        val target = entries(message("tool-1", ConversationItemKind.REASONING), first)
        assertEquals(HistoryReadingAnchor(target[0].id, 0, -13), historyReadingAnchor(old, target,
            listOf(HistoryVisibleRow(old[0].id, -13))))
    }

    @Test fun removedRowsCannotProduceAnInvalidAnchor() {
        assertNull(historyReadingAnchor(entries(message("a")), entries(message("b")),
            listOf(HistoryVisibleRow("a", 0))))
    }

    @Test fun changingRowDoesNotDisplaceAnUnchangedVisibleBody() {
        val old = entries(message("a"), message("b"))
        val target = entries(message("older"), message("a", text = "changed"), message("b"))
        assertEquals(HistoryReadingAnchor("b", 2, 140), historyReadingAnchor(old, target,
            listOf(HistoryVisibleRow("a", -20), HistoryVisibleRow("b", 140))))
    }
}
