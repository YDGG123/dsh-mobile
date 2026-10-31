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

    // ---- AndroidTester 两侧对拍（30 帧）钉住的 6 处回归（2026-11-01）----
    // 复现材料：android-verify/scratch/{frames.tsv,gen-frames.py,CardProbe.kt,IosCardProbe.swift}

    @Test
    fun viewSkipsEmptyTitleToText_b2() {
        // iOS: compactMap{as? String}.first{!isEmpty} —— 空串跳过继续找，不是丢卡。
        val parsed = card("""{ "id":"b2", "kind":"view", "botId":"b", "view":{ "title":"", "text":"X" } }""")
        assertEquals("X", parsed?.prompt)
    }

    @Test
    fun viewSkipsEmptyTitleAndTextToBody_b3() {
        val parsed = card("""{ "id":"b3", "kind":"view", "botId":"b", "view":{ "title":"", "text":"", "body":"B" } }""")
        assertEquals("B", parsed?.prompt)
    }

    @Test
    fun secretSkipsEmptyNameToDescription_b7() {
        val parsed = card("""{ "id":"b7", "kind":"secret", "botId":"b", "secret":{ "name":"", "description":"D" } }""")
        assertEquals("D", parsed?.prompt)
        assertEquals("等你填密钥", parsed?.statusText)
    }

    @Test
    fun numericTitleIsSkippedNotStringified_b5() {
        // iOS `as? String` 类型严格：数字 123 不是 String → 跳到 text。不许 content 转换。
        val parsed = card("""{ "id":"b5", "kind":"view", "botId":"b", "view":{ "title":123, "text":"X" } }""")
        assertEquals("X", parsed?.prompt)
    }

    @Test
    fun numericIdDropsCard_b15() {
        val parsed = card("""{ "id":123, "kind":"view", "botId":"b", "view":{ "title":"t" } }""")
        assertNull(parsed, "数字 id 在 iOS as? String 下取不到 → 整卡丢弃")
    }

    @Test
    fun stringCustomIsNotBoolean_b22() {
        // iOS `as? Bool` 严格：字符串 "true" 不是 Bool → false。
        val parsed = card(
            """{ "id":"b22", "kind":"question", "botId":"b", "questions":[{ "question":"Q", "custom":"true" }] }"""
        )
        assertEquals(false, parsed?.allowsCustom)
    }

    // ---- AndroidTester 加严批次（27 帧）钉住的 NSNumber→Bool 桥接边界 ----

    @Test
    fun numericOneIsTrue_likeIosNSNumberBridging() {
        // iOS 经 NSNumber→Bool 桥接只认数字 1；kotlinx booleanOrNull 对数字返回 null，须显式桥。
        val parsed = card(
            """{ "id":"n1", "kind":"question", "botId":"b", "questions":[{ "question":"Q", "custom":1, "multiple":1 }] }"""
        )
        assertEquals(true, parsed?.allowsCustom)
        assertEquals(true, parsed?.allowsMultiple)
    }

    @Test
    fun numericZeroTwoAndOthersStayFalse() {
        val parsed = card(
            """{ "id":"n2", "kind":"question", "botId":"b", "questions":[{ "question":"Q", "custom":0 }] }"""
        )
        assertEquals(false, parsed?.allowsCustom)
        val parsed2 = card(
            """{ "id":"n3", "kind":"question", "botId":"b", "questions":[{ "question":"Q", "custom":2 }] }"""
        )
        assertEquals(false, parsed2?.allowsCustom)
        val parsed3 = card(
            """{ "id":"n4", "kind":"question", "botId":"b", "questions":[{ "question":"Q", "custom":"1" }] }"""
        )
        assertEquals(false, parsed3?.allowsCustom, "字符串 \"1\" 依旧不算（iOS as? Bool 严格）")
    }

    @Test
    fun questionEmptyStringDoesNotFallBackToTitle() {
        // iOS `question ?? title` 是 nil 合并：question="" 不回退 title，判空丢卡。
        // 与 view/secret 的「跳空串继续找」是**两种不同语义**，这条测试防止有人"顺手统一"。
        val parsed = card(
            """{ "id":"q-e", "kind":"question", "botId":"b", "questions":[{ "question":"", "title":"T" }] }"""
        )
        assertNull(parsed)
    }
}
