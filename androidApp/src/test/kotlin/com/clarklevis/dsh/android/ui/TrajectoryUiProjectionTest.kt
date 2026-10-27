package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryNodeKind
import com.clarklevis.dsh.shared.projection.TrajectoryRequest
import com.clarklevis.dsh.shared.projection.RequestTokenUsage
import com.clarklevis.dsh.shared.protocol.GatewayEvent
import com.clarklevis.dsh.shared.protocol.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryUiProjectionTest {
    @Test
    fun overviewMatchesIosDurationTurnAndToolRules() {
        val input = node("input", TrajectoryNodeKind.INPUT, 1, 1.0, 2.0)
        val request = requestNode("request", turn = 1, step = 1, start = 2.0, end = 6.0)
        val assistant = node("assistant", TrajectoryNodeKind.ASSISTANT, 2, 2.0, 4.0, turn = 1, step = 1)
        val tool = node("tool", TrajectoryNodeKind.TOOL, 3, 4.0, 7.0, turn = 1, step = 1)
        val nextTurn = node("next", TrajectoryNodeKind.INPUT, 4, 8.0, 9.0, turn = 2)

        val summary = TrajectoryUiProjection.overview(listOf(input, request, assistant, tool, nextTurn))

        assertEquals(7.0, summary.durationSeconds, 0.0001)
        assertEquals(2, summary.turnCount)
        assertEquals(1, summary.toolCount)
        assertEquals(listOf(input, assistant, tool, nextTurn), summary.visibleNodes)
        assertEquals(0f, summary.entries.first().startFraction)
        assertEquals(1f, summary.entries.last().endFraction)
        assertTrue(summary.entries.zipWithNext().all { (left, right) -> left.endFraction == right.startFraction })
    }

    @Test
    fun hiddenRequestMapsToAssistantAndTurnHeadersFollowFutureTurn() {
        val inputWithoutTurn = node("input", TrajectoryNodeKind.INPUT, 1, 1.0, 1.0)
        val request = requestNode("request", turn = 3, step = 2, start = 2.0, end = 3.0)
        val assistant = node("assistant", TrajectoryNodeKind.ASSISTANT, 2, 2.0, 3.0, turn = 3, step = 2)
        val rows = TrajectoryUiProjection.displayRows(listOf(inputWithoutTurn, request, assistant))

        assertEquals(2, rows.size)
        assertEquals(3, rows.first().turn)
        assertTrue(rows.first().startsTurn)
        assertSame(request, rows.last().request)
        assertEquals(3, rows.last().turn)
    }

    @Test
    fun liveNodeReplacementImmediatelyChangesOverviewAndRowContent() {
        val streaming = node("assistant-stream", TrajectoryNodeKind.ASSISTANT, 1, 10.0, 10.2, turn = 1, step = 1)
            .copy(subtitle = "正在生成")
        val first = TrajectoryUiProjection.overview(listOf(streaming))
        val completed = streaming.copy(subtitle = "生成完成", endEpochSeconds = 12.5)
        val second = TrajectoryUiProjection.overview(listOf(completed))

        assertEquals("正在生成", first.visibleNodes.single().subtitle)
        assertEquals("生成完成", second.visibleNodes.single().subtitle)
        assertTrue(second.durationSeconds > first.durationSeconds)
        assertEquals(completed, TrajectoryUiProjection.displayRows(listOf(completed)).single().node)
    }

    @Test
    fun overviewTapUsesRequestedLaneBeforeNearestFallback() {
        val input = node("input", TrajectoryNodeKind.INPUT, 1, 1.0, 2.0)
        val assistant = node("assistant", TrajectoryNodeKind.ASSISTANT, 2, 2.0, 3.0)
        val tool = node("tool", TrajectoryNodeKind.TOOL, 3, 3.0, 4.0)
        val entries = TrajectoryUiProjection.overview(listOf(input, assistant, tool)).entries

        assertSame(tool, TrajectoryUiProjection.nearestEntry(entries, 0.05f, TrajectoryNodeKind.TOOL))
        assertSame(assistant, TrajectoryUiProjection.nearestEntry(entries, 0.95f, TrajectoryNodeKind.ASSISTANT))
    }

    @Test
    fun longToolDoesNotCompressOtherOperationsUnlessDurationIsSelected() {
        val nodes = listOf(
            node("model", TrajectoryNodeKind.ASSISTANT, 1, 1.0, 1.1),
            node("long-tool", TrajectoryNodeKind.TOOL, 2, 1.1, 141.1),
            node("next-tool", TrajectoryNodeKind.TOOL, 3, 141.1, 141.3)
        )
        val sequence = TrajectoryUiProjection.overview(nodes)
        val duration = TrajectoryUiProjection.overview(nodes, usesActualDuration = true)

        val widths = sequence.entries.map { it.endFraction - it.startFraction }
        widths.forEach { assertEquals(1f / 3, it, 0.0001f) }
        assertTrue(duration.entries[1].endFraction - duration.entries[1].startFraction > 0.98f)
        assertEquals(sequence.durationSeconds, duration.durationSeconds, 0.0001)
        val firstTool = sequence.entries[1].barBounds(300f)
        val nextTool = sequence.entries[2].barBounds(300f)
        assertTrue(nextTool.start - firstTool.endInclusive >= 1.9f)
        assertEquals(300f, nextTool.endInclusive + 1f, 0.001f)
    }

    @Test
    fun overviewTapIncludesContextAndSubtoolInTheirVisibleLanes() {
        val context = node("context", TrajectoryNodeKind.CONTEXT, 1, 1.0, 1.0)
        val assistant = node("model", TrajectoryNodeKind.ASSISTANT, 2, 1.0, 2.0)
        val subtool = node("subtool", TrajectoryNodeKind.SUBTOOL, 3, 2.0, 4.0)
        val entries = TrajectoryUiProjection.overview(listOf(context, assistant, subtool)).entries

        assertSame(context, TrajectoryUiProjection.nearestEntry(entries, 0.1f, TrajectoryNodeKind.INPUT))
        assertSame(subtool, TrajectoryUiProjection.nearestEntry(entries, 0.1f, TrajectoryNodeKind.TOOL))
    }

    private fun requestNode(
        id: String,
        turn: Int,
        step: Int,
        start: Double,
        end: Double
    ): TrajectoryNode = node(id, TrajectoryNodeKind.REQUEST, 99, start, end, turn, step).copy(
        request = TrajectoryRequest(
            number = 1,
            turn = turn,
            step = step,
            provider = "deepseek",
            model = "model",
            options = null,
            usage = RequestTokenUsage(),
            cumulativeUsage = RequestTokenUsage(),
            toolCalls = 0,
            subtoolCalls = 0
        )
    )

    private fun node(
        id: String,
        kind: TrajectoryNodeKind,
        sequence: Int,
        start: Double,
        end: Double,
        turn: Int? = null,
        step: Int? = null
    ): TrajectoryNode {
        val record = SessionEvent(
            sessionId = "session",
            seq = sequence,
            time = end,
            event = GatewayEvent("fixture", turn = turn, step = step)
        )
        return TrajectoryNode(
            id = id,
            kind = kind,
            title = kind.name,
            subtitle = id,
            startSequence = sequence,
            endSequence = sequence,
            startEpochSeconds = start,
            endEpochSeconds = end,
            records = listOf(record)
        )
    }
}
