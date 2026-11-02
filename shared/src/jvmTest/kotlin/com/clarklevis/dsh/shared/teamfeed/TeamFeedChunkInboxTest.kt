package com.clarklevis.dsh.shared.teamfeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 缺口 2 闭环判据（Chief 定 A 方案的那条）：**一条超 512KB 的帧走完整路径能还原出来**，
 * 而不是只测重组器本身。入口就是 transport 实际调用的 [TeamFeedChunkInbox.onText]。
 */
class TeamFeedChunkInboxTest {

    /** 非帧文本照常放行（普通帧不被吞）。 */
    @Test
    fun plainFrameIsDeliveredVerbatim() {
        val inbox = TeamFeedChunkInbox()
        val text = """{"v":1,"kind":"delta","instanceId":"i","seq":1,"base":0,"degraded":false,"ops":[]}"""
        assertEquals(TeamFeedChunkInbox.Result.Deliver(text), inbox.onText(text))
    }

    /**
     * **鲁棒性**：用户消息里可能出现字面量 `"kind":"chunk"`——按 contains 嗅探会误吞，
     * JSON 解析判定不会（它不是合法 chunk 形状，缺 streamId/index/total/part）。
     */
    @Test
    fun payloadMentioningChunkIsNotSwallowed() {
        val inbox = TeamFeedChunkInbox()
        val text = ("{\"v\":1,\"kind\":\"delta\",\"instanceId\":\"i\",\"seq\":1,\"base\":0,\"degraded\":false," +
                "\"ops\":[{\"op\":\"bot.patch\",\"id\":\"b1\",\"set\":{\"activity\":\"{\\\"kind\\\":\\\"chunk\\\"}\"}}]}")
        assertEquals(TeamFeedChunkInbox.Result.Deliver(text), inbox.onText(text))
    }

    /**
     * **主判据**：一条超过 512KB（默认 maxFrameBytes）的帧，被网关切成 chunk 发来，
     * 走 [TeamFeedChunkInbox.onText] 完整路径后还原成与原文逐字节相同的 JSON。
     * 用默认上限（不缩参），真走 512KB 这条线。
     */
    @Test
    fun oversizedFrameRoundTripsThroughDefault512KPath() {
        val inbox = TeamFeedChunkInbox() // 默认 maxChunkBytes = 512KB
        // 组一条 >512KB 的合法 delta 帧：activity 塞 600KB 文本。
        val bigActivity = "中".repeat(300_000) // UTF-8 600KB，含多字节字符（字符边界切分的真考验）
        val frame = """{"v":1,"kind":"delta","instanceId":"i","seq":1,"base":0,"degraded":false,
                       "ops":[{"op":"bot.patch","id":"b1","set":{"activity":"$bigActivity"}}]}""".replace("\n", "")
        assertTrue(frame.encodeToByteArray().size > 512 * 1024)

        // 网关侧同款切块（chunked.mjs 的 utf8Parts 规则：按码点、不劈代理对），
        // 这里直接按字符切近似——chunk 大小 512KB 字节 ≈ 512K/3 字符以内，凑块数即可。
        val parts = frame.chunked(170_000) // ≤512KB 字节/块
        var delivered: String? = null
        for ((index, part) in parts.withIndex()) {
            // part 里全是引号：chunk 帧必须用 JSON 编码器构造（手拼不转义 = 非法 JSON = 被当普通帧放行）。
            val chunkJson = chunkJson("s1", index, parts.size, part)
            when (val result = inbox.onText(chunkJson)) {
                is TeamFeedChunkInbox.Result.Complete -> delivered = result.json
                is TeamFeedChunkInbox.Result.Pending -> Unit
                else -> throw AssertionError("意外结果: $result @ $index")
            }
        }
        assertEquals(frame, delivered)
    }

    /** 出错路径：mid-stream total 变更 ⇒ Failed（流已丢），后续块按新流重开不串台。 */
    @Test
    fun failedStreamIsReportedAndDiscarded() {
        val inbox = TeamFeedChunkInbox()
        assertEquals(TeamFeedChunkInbox.Result.Pending, inbox.onText(chunkJson("s1", 0, 3, "a")))
        val result = inbox.onText(chunkJson("s1", 1, 2, "b"))
        assertTrue(result is TeamFeedChunkInbox.Result.Failed)
        // 丢弃后：同流重开为一条新流（2 块）能正常收齐。
        assertEquals(TeamFeedChunkInbox.Result.Pending, inbox.onText(chunkJson("s1", 0, 2, "x")))
        val done = inbox.onText(chunkJson("s1", 1, 2, "y"))
        assertEquals(TeamFeedChunkInbox.Result.Complete("xy"), done)
    }

    private fun chunkJson(streamId: String, index: Int, total: Int, part: String): String =
        kotlinx.serialization.json.buildJsonObject {
            put("kind", kotlinx.serialization.json.JsonPrimitive("chunk"))
            put("streamId", kotlinx.serialization.json.JsonPrimitive(streamId))
            put("index", kotlinx.serialization.json.JsonPrimitive(index))
            put("total", kotlinx.serialization.json.JsonPrimitive(total))
            put("finalKind", kotlinx.serialization.json.JsonPrimitive("delta"))
            put("part", kotlinx.serialization.json.JsonPrimitive(part))
        }.toString()
}
