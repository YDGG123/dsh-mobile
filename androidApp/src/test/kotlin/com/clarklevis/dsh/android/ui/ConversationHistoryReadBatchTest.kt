package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryReadBatchTest {
    private val batch = HistoryReadBatch(setOf("existing"), 100, 400f, 320f, 1f, 0)
    private fun item(id: String, text: String = "短消息", kind: ConversationItemKind = ConversationItemKind.USER) =
        ConversationItem(id, kind, "", text, epochSeconds = 1.0)
    private fun stop(window: HistoryReadWindow, state: HistoryReadBatch = batch, before: Int? = 50,
        more: Boolean = true, failed: Boolean = false, now: Long = 100) =
        historyReadBatchStopReason(state, window, before, more, failed, now)

    @Test fun hundredsOfToolsDoNotMasqueradeAsHundredsOfReadableMessages() {
        val tools = (1..200).map { item("tool-$it", kind = ConversationItemKind.TOOL) }
        val window = historyReadWindow(batch, tools + item("existing"))
        assertEquals(1, makeConversationDisplayEntries(tools).size)
        assertEquals(0, window.messages)
        assertEquals(0f, window.estimatedHeightDp, 0f)
        assertNull(stop(window))
    }

    @Test fun shortMessagesAccumulateUntilTheyProvideAUsefulReadingWindow() {
        val first = historyReadWindow(batch, listOf(item("new"), item("existing")))
        val enough = historyReadWindow(batch, (1..16).map { item("new-$it") } + item("existing"))
        assertNull(stop(first))
        assertEquals("window-filled", stop(enough))
        assertEquals(16, enough.messages)
    }

    @Test fun oneLongReplyCanFillTheWindowWithoutCountingMarkdownChunks() {
        val reply = item("reply", "正文内容。\n\n".repeat(200), ConversationItemKind.ASSISTANT)
        val window = historyReadWindow(batch, listOf(reply, item("existing")))
        assertEquals(1, window.messages)
        assertEquals("window-filled", stop(window))
    }

    @Test fun existingContentAndNewLiveTailCannotSatisfyTheOlderWindow() {
        val window = historyReadWindow(batch, listOf(item("new"),
            item("existing", "已经展示的很长内容".repeat(2_000)), item("live", "新回复".repeat(2_000))))
        assertEquals(1, window.messages)
        assertNull(stop(window))
    }

    @Test fun endErrorOrUnchangedCursorStopsAutomaticallyFetching() {
        val empty = HistoryReadWindow(0, 0f, 0)
        assertEquals("end", stop(empty, more = false))
        assertEquals("error", stop(empty, failed = true))
        assertEquals("no-progress", stop(empty, before = 100))
        assertEquals("no-progress", stop(empty, before = 101))
        assertEquals("no-progress", stop(empty, before = null))
    }

    @Test fun endlessSparsePagesHitABoundedPageCount() {
        assertNull(stop(HistoryReadWindow(0, 0f, 0), batch.copy(pages = 6)))
        assertEquals("page-budget", stop(HistoryReadWindow(0, 0f, 0), batch.copy(pages = 7)))
    }

    @Test fun slowOrOversizedToolPagesHaveBoundedPreparationCost() {
        assertEquals("time-budget", stop(HistoryReadWindow(0, 0f, 0), now = 2_500))
        val tools = listOf(item("tool", "x".repeat(1_048_576), ConversationItemKind.TOOL_RESULT))
        val window = historyReadWindow(batch, tools)
        assertEquals(0, window.messages)
        assertEquals("content-budget", stop(window))
    }

    @Test fun viewportWidthAndFontScaleAffectTheReadingTarget() {
        val messages = listOf(item("new", "长短混排 text ".repeat(40)))
        val normal = historyReadWindow(batch, messages)
        val narrow = historyReadWindow(batch.copy(contentWidthDp = 200f), messages)
        val largeFont = historyReadWindow(batch.copy(fontScale = 1.5f), messages)
        assertTrue(narrow.estimatedHeightDp > normal.estimatedHeightDp)
        assertTrue(largeFont.estimatedHeightDp > normal.estimatedHeightDp)
    }
}
