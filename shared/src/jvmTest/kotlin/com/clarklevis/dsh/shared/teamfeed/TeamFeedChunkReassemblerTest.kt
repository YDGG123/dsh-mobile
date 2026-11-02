package com.clarklevis.dsh.shared.teamfeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 缺口 2（chunked-frames-v1 重组）的判据用例 —— 语义逐条对齐 iOS `FrameReassembler.swift`
 * 与网关 `chunked.mjs`（2026-11-03 实读后手写），负例必红。
 */
class TeamFeedChunkReassemblerTest {

    private fun chunk(streamId: String = "s1", index: Int, total: Int, part: String, finalKind: String = "snapshot") =
        TeamFeedChunk(streamId, index, total, finalKind, part)

    /** 顺序收齐 ⇒ 拼回原文（UTF-8 字符边界由网关保证，这里拼字符串天然安全）。 */
    @Test
    fun inOrderChunksAssembleToPayload() {
        val r = TeamFeedChunkReassembler()
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 0, total = 2, part = "{\"a\":")))
        val done = r.accept(chunk(index = 1, total = 2, part = "\"中文🤖\"}"))
        val payload = (done as TeamFeedChunkOutcome.Success).payload
        assertEquals("{\"a\":\"中文🤖\"}", payload)
        assertTrue(payload!!.encodeToByteArray().size > 10, "重组上限按 UTF-8 字节计")
    }

    /** 乱序到达按 index 归位；重复块幂等覆盖、不重复计数。 */
    @Test
    fun outOfOrderAndDuplicateChunksAreSafe() {
        val r = TeamFeedChunkReassembler()
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 2, total = 3, part = "c")))
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 0, total = 3, part = "a")))
        // 重复块：幂等覆盖，不该把 received 计第二遍（否则永远收不齐/提前收齐）。
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 0, total = 3, part = "a")))
        val done = r.accept(chunk(index = 1, total = 3, part = "b"))
        assertEquals("abc", (done as TeamFeedChunkOutcome.Success).payload)
    }

    /** 单块超限 ⇒ 该流丢弃 + 明确错误（说明网关和宣传值不一致）。
     *  注意 maxChunkBytes 有 1024 下限（clamped，iOS 同规），用例必须给 ≥ 下限的阈值。 */
    @Test
    fun oversizedChunkIsRejectedAndStreamDiscarded() {
        val r = TeamFeedChunkReassembler(TeamFeedChunkLimits(maxChunkBytes = 1024))
        val failure = r.accept(chunk(index = 0, total = 2, part = "x".repeat(2000))) as TeamFeedChunkOutcome.Failure
        assertTrue(failure.error is TeamFeedReassemblyError.ChunkTooLarge)
        // 流已被丢：后续同流块按新流重开（不再带旧状态）。
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 0, total = 2, part = "12")))
    }

    /** 累计超限 ⇒ 丢流（体积先算后存，不无限吃内存）。
     *  maxReassembledBytes 下限 64KiB（clamped），用例用 32KiB 块 × 2 累计 72KiB 越限。 */
    @Test
    fun streamTooLargeIsRejected() {
        val r = TeamFeedChunkReassembler(
            TeamFeedChunkLimits(maxChunkBytes = 32 * 1024, maxReassembledBytes = 64 * 1024),
        )
        // 每块都 ≤ maxChunkBytes（32KiB），但三块累计 96KiB > 64KiB ⇒ 逐块检查累计值。
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 0, total = 3, part = "x".repeat(32 * 1024))))
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 1, total = 3, part = "y".repeat(32 * 1024))))
        val failure = r.accept(chunk(index = 2, total = 3, part = "z".repeat(32 * 1024))) as TeamFeedChunkOutcome.Failure
        assertTrue(failure.error is TeamFeedReassemblyError.StreamTooLarge)
    }

    /** 同一 streamId 换 total/finalKind ⇒ 协议错乱，丢弃重来。 */
    @Test
    fun midStreamTotalChangeIsMalformed() {
        val r = TeamFeedChunkReassembler()
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 0, total = 3, part = "a")))
        val failure = r.accept(chunk(index = 1, total = 2, part = "b")) as TeamFeedChunkOutcome.Failure
        assertTrue(failure.error is TeamFeedReassemblyError.Malformed)
    }

    /** index 越界 / total 非法 / finalKind 空 ⇒ 明确错误。 */
    @Test
    fun malformedChunkIsRejected() {
        val r = TeamFeedChunkReassembler()
        val a = r.accept(chunk(index = 5, total = 2, part = "x")) as TeamFeedChunkOutcome.Failure
        assertTrue(a.error is TeamFeedReassemblyError.IndexOutOfRange)
        val b = r.accept(chunk(index = 0, total = 0, part = "x")) as TeamFeedChunkOutcome.Failure
        assertTrue(b.error is TeamFeedReassemblyError.IndexOutOfRange)
        val c = r.accept(TeamFeedChunk("s9", 0, 1, "", "x")) as TeamFeedChunkOutcome.Failure
        assertTrue(c.error is TeamFeedReassemblyError.IndexOutOfRange)
    }

    /** 并发流超限 ⇒ 拒绝（网关按帧串行发，上限只是防御）。 */
    @Test
    fun tooManyStreamsIsRejected() {
        val r = TeamFeedChunkReassembler(TeamFeedChunkLimits(maxStreams = 1))
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(streamId = "s1", index = 0, total = 2, part = "a")))
        val failure = r.accept(chunk(streamId = "s2", index = 0, total = 2, part = "b")) as TeamFeedChunkOutcome.Failure
        assertTrue(failure.error is TeamFeedReassemblyError.TooManyStreams)
    }

    /** 超时清扫：首块后 streamTimeout 内没收齐 ⇒ 丢流（调用方重拉快照）。 */
    @Test
    fun expiredStreamsAreSwept() {
        var now = 0L
        val r = TeamFeedChunkReassembler(nowMillis = { now })
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 0, total = 2, part = "a")))
        now = 29_999
        assertTrue(r.expire().isEmpty(), "窗口内不清")
        now = 30_001
        assertEquals(listOf("s1"), r.expire())
        // 清扫后同流重开。
        assertEquals(TeamFeedChunkOutcome.Success(null), r.accept(chunk(index = 0, total = 2, part = "a")))
    }

    /** 夹取：外部给的离谱值不许绕过硬帽（iOS P2 的根因）。 */
    @Test
    fun limitsAreClampedThroughTheOnlyEntry() {
        val crazy = TeamFeedChunkLimits(
            maxChunkBytes = 64 * 1024 * 1024,
            maxReassembledBytes = 1024 * 1024 * 1024,
            maxStreams = 99,
            streamTimeoutSeconds = 9_999,
        ).clamped
        assertEquals(TeamFeedChunkLimits.ABSOLUTE_CHUNK_CAP, crazy.maxChunkBytes)
        assertEquals(TeamFeedChunkLimits.ABSOLUTE_REASSEMBLED_CAP, crazy.maxReassembledBytes)
        assertEquals(4, crazy.maxStreams)
        assertEquals(300L, crazy.streamTimeoutSeconds)
        // /info 合并：缺字段不更新，给的字段过夹取。
        val merged = TeamFeedChunkLimits().mergedWith(mapOf("maxFrameBytes" to 1024.0, "maxReassembledBytes" to null))
        assertEquals(1024, merged.maxChunkBytes)
        assertEquals(4 * 1024 * 1024, merged.maxReassembledBytes)
    }
}
