package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.parseBridgeCardQuestion
import com.clarklevis.dsh.shared.protocol.parseBridgeCardsFrame
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

/**
 * E1 对照实验（功能层第八节）：同一份帧喂 Android 解析器，断言与 iOS
 * `GatewayTeamFeed.cardQuestion(from:)` 的实测输出一致（secret/view/question/未知 4/4）。
 * 帧 JSON 原样取自 android-parity-functional.md §8 开头，不改一字。
 */
class BridgeCardFrameParseTest {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/bridge-c1/$name")?.readBytes()?.decodeToString()
            ?: error("missing resource bridge-c1/$name")

    private fun card(json: String) = parseBridgeCardQuestion(wireJson.parseToJsonElement(json).jsonObject)

    @Test
    fun secretFrameMatchesIosExpectation() {
        val parsed = card(
            """{ "id":"card-secret-1", "kind":"secret", "botId":"bot-1", "sessionId":"sess-1",
                "secret":{ "name":"OPENAI_API_KEY", "description":"用于调用模型服务" } }"""
        )
        assertEquals("secret", parsed?.kind)
        assertEquals("OPENAI_API_KEY", parsed?.prompt)
        assertEquals("bot-1", parsed?.owner)
        assertEquals("sess-1", parsed?.sessionId)
        assertEquals("等你填密钥", parsed?.statusText)
    }

    @Test
    fun viewFrameMatchesIosExpectation() {
        val parsed = card(
            """{ "id":"card-view-1", "kind":"view", "botId":"bot-1", "sessionId":"sess-1",
                "view":{ "title":"方案对比", "body":"A 方案……" } }"""
        )
        assertEquals("view", parsed?.kind)
        assertEquals("方案对比", parsed?.prompt)
        assertEquals("bot-1", parsed?.owner)
        assertEquals("等你确认卡片", parsed?.statusText)
    }

    @Test
    fun viewFrameFallsBackThroughTitleTextBody() {
        // iOS 取 title/text/body 第一个非空；只有 body 时也能出卡。
        val parsed = card("""{ "id":"v2", "kind":"view", "botId":"b", "view":{ "body":"正文" } }""")
        assertEquals("正文", parsed?.prompt)
    }

    @Test
    fun questionFrameMatchesIosExpectation() {
        val parsed = card(
            """{ "id":"card-q-1", "kind":"question", "botId":"bot-1", "sessionId":"sess-1",
                "questions":[ { "question":"选哪个？", "options":[{"label":"A"},{"label":"B"}] } ] }"""
        )
        assertEquals("question", parsed?.kind)
        assertEquals("选哪个？", parsed?.prompt)
        assertEquals(listOf("A", "B"), parsed?.options)
        assertEquals("等你回答", parsed?.statusText)
    }

    @Test
    fun roomCardPrefersRoomIdAsOwner() {
        // iOS cardOwner：roomId 优先于 botId。
        val parsed = card("""{ "id":"r1", "kind":"view", "roomId":"room-9", "botId":"bot-1", "view":{ "title":"t" } }""")
        assertEquals("room-9", parsed?.owner)
    }

    @Test
    fun unknownKindAndOwnerlessReturnNull() {
        assertNull(card("""{ "id":"u1", "kind":"mystery", "botId":"bot-1", "view":{ "title":"t" } }"""))
        assertNull(card("""{ "id":"u2", "kind":"view", "view":{ "title":"t" } }"""), "无 owner 的卡必须丢弃（iOS cardOwner=nil）")
        assertNull(card("""{ "kind":"view", "botId":"b", "view":{ "title":"t" } }"""), "无 id 的卡必须丢弃")
    }

    @Test
    fun cardSnapshotFrameParsesAllCards() {
        val parsed = parseBridgeCardsFrame(
            """{ "v":1, "kind":"card-snapshot", "instanceId":"i", "seq":8, "at":1793480558428, "cards":[
                 { "id":"c1", "kind":"view", "botId":"bot-1", "sessionId":"s", "view":{ "title":"方案对比", "body":"A" } },
                 { "id":"c2", "kind":"question", "botId":"bot-2", "questions":[{ "question":"q", "options":[{"label":"A"}] }] }
               ] }"""
        )
        assertEquals(2, parsed.size)
        assertEquals("方案对比", parsed[0].prompt)
        assertEquals("q", parsed[1].prompt)
    }

    @Test
    fun garbageFrameYieldsEmptyList() {
        assertTrue(parseBridgeCardsFrame("not json").isEmpty())
        assertTrue(parseBridgeCardsFrame("""{"kind":"card-snapshot"}""").isEmpty())
        assertTrue(parseBridgeCardsFrame("""{"cards":[]}""").isEmpty())
    }

    @Test
    fun liveGatewaySnapshotParses() {
        // 2026-11-01 从本机网关 /mobile/v1/cards 原样抓的快照（3 张 question 卡）。
        val parsed = parseBridgeCardsFrame(resource("cards.json"))
        assertTrue(parsed.isNotEmpty(), "live snapshot should parse at least one card")
        assertTrue(parsed.all { it.id.isNotBlank() && it.prompt.isNotBlank() }, "every parsed card needs id+prompt")
        assertTrue(parsed.all { it.owner.startsWith("session-") }, "live owners are session ids: ${parsed.map { it.owner }}")
    }
}
