package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedConversationPatch
import com.clarklevis.dsh.shared.facade.SharedConversationStore
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.protocol.RawSessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 群聊成员名（`senderName`）的**回归用例**（2026-11-02）。
 *
 * 背景：群聊里 Bot 以 `user/message` 形态转发发言（`source.kind=="bot" && role=="post" && form=="relay"`），
 * 投影层原先把它当「上下文注入」渲染（CONTEXT + 前缀未剥离），**两端都看不到成员发言与名字**。
 * 修法在共享层收口：这一形态 ⇒ `ASSISTANT` + `senderName = source.senderName`；
 * 系统事件（failed/resumed/stopped/done/again）**不得**被当成 Bot 发言。
 *
 * 数据：`/room-2a10aef5.jsonl` = 宿主群聊 `session-2a10aef5`（Orcarness 开发组）1090 条**真实记录**。
 * 为什么用真数据而不是构造数据：构造数据只能证明「我按我理解的字段写的解析器能用」，
 * 而真数据能证明**网关实际下发的字段形态**被我理解对了（这条 bug 的根因正是形态理解偏了）。
 *
 * 三条判据对应 Chief 2026-11-02 的裁定（KMP 统一产出，两端渲染层只消费）：
 * 1. 该出现的：Bot 转发发言 ⇒ ASSISTANT 且 `senderName` 是**服务端给的具体名字**（iOSDev/ReleaseBot…）
 * 2. 不该出现的人名：系统事件 / 用户自己发的 / 上下文 ⇒ `senderName == null`（否则渲染层会误署名）
 * 3. 负对照：修复前它们落在「`CONTEXT` 且正文带 `[Group post from` 前缀」——现在是 **0**
 */
class RoomRelaySenderNameTest {
    private val sessionId = "session-2a10aef5-2e31-4551-8af6-880da873f4d8"

    /** fixture 里 relay post 的**精确**条数（1090 条真数据里的 106 条，见 `assertFixtureShape`）。
     *  用精确值而不是下界：下界在「只剩 3 条仍然绿」时不会报警（AndroidTester 指出的假绿）。 */
    private val fixtureRelayPosts = 106
    /** fixture 里出现过的全部名字（精确表，改动它会立刻反映在这里）。 */
    private val fixtureNames = mapOf(
        "AndroidTester" to 53, "iOSCoder" to 32, "ReleaseBot" to 12,
        "AndroidCoder" to 4, "iOSDev" to 3, "iOSTester" to 1, "Chief" to 1
    )

    private fun recordsOf(jsonl: String, id: String) =
        jsonl.lines().filter(String::isNotBlank).map {
            wireJson.decodeFromString(RawSessionEvent.serializer(), it).normalized(id)
        }

    private fun project(records: List<com.clarklevis.dsh.shared.protocol.SessionEvent>): List<ConversationItem> {
        val store = SharedConversationStore()
        val received = mutableListOf<SharedMviEvent>()
        store.subscribe(SharedMviEventObserver(received::add))
        store.replaceTypedSession(sessionId, records)
        val patch = wireJson.decodeFromString(
            SharedConversationPatch.serializer(),
            received.last().statePayloadJson ?: error("statePayloadJson 缺失")
        )
        return (patch.replacementItems ?: emptyList()) + patch.operations.mapNotNull { it.item }
    }

    private fun fixtureJsonl(): String = javaClass.getResource("/room-2a10aef5.jsonl")!!.readText()

    private fun projectedItems(): List<ConversationItem> = project(recordsOf(fixtureJsonl(), sessionId))

    /**
     * **非空自证**（AndroidTester 的 ②）：先证明"该被渲染的那批真的被读到了"。
     * 没有这一步，fixture 一旦解析退化（relay 判定全 false），后面 `assertEquals(0, legacy)` 照样绿——
     * 那正是「空集当通过」的经典假绿。
     */
    private fun assertFixtureShape(items: List<ConversationItem>) {
        // 原始记录层面的形态（不经过投影）：fixture 里 relay post 恰 106 条，且都带 senderName
        val records = recordsOf(fixtureJsonl(), sessionId)
        val relayPosts = records.filter { record ->
            val s = record.event.raw?.get("source")
            s?.get("kind")?.stringValue == "bot" && s.get("role")?.stringValue == "post" &&
                s.get("form")?.stringValue == "relay"
        }
        assertEquals(fixtureRelayPosts, relayPosts.size, "fixture 形态变了：relay post 数不再是 $fixtureRelayPosts")
        assertTrue(relayPosts.isNotEmpty(), "INVALID：一条 relay post 都没读到 ⇒ 下面所有断言都不算数")
        assertEquals(fixtureRelayPosts, items.count { it.kind == ConversationItemKind.ASSISTANT && it.senderName != null },
            "ASSISTANT+senderName 的条数必须与 relay post 数一致")
    }

    @Test
    fun botRelayPostsBecomeAssistantBubblesCarryingServerSideName() {
        val items = projectedItems()
        assertFixtureShape(items)   // ← 非空自证必须在任何"等于某值"的断言之前

        val named = items.filter { it.kind == ConversationItemKind.ASSISTANT && it.senderName != null }
        // 判据 1：该出现的。**精确条数与精确名字表**（下界挡不住"少一整类仍然绿"）
        val actualNames = named.mapNotNull { it.senderName }.groupingBy { it }.eachCount()
        assertEquals(fixtureNames, actualNames, "名字分布应与真数据一致（来源=服务端 author.name）")

        // 正文里的转发前缀必须已剥离——否则气泡里会显示一段"内部格式"文本
        assertTrue(named.none { it.text.startsWith("[Group post from") },
            "ASSISTANT 气泡正文不应残留 `[Group post from …]` 前缀")
    }

    @Test
    fun onlyAssistantItemsCarryAName() {
        val items = projectedItems()
        assertFixtureShape(items)

        // 判据 2：不该有的人名不能出现（用户自己发的 / 上下文注入 / 系统提示一律不署名）
        val wronglyNamed = items.filter { it.senderName != null && it.kind != ConversationItemKind.ASSISTANT }
        assertEquals(emptyList(), wronglyNamed.map { "${it.kind}:${it.senderName}" },
            "只有 Bot 发言（ASSISTANT）可以带名字；其余带名 ⇒ 渲染层会误署名")

        // 规则 1：用户自己发的不署名（渲染层显示「我」），否则会与网关那条用户条目重复显示
        assertEquals(0, items.count { it.kind == ConversationItemKind.USER && it.senderName != null })
        // 非空自证：fixture 里确实有 USER 条目可判（否则上面那条断言等于没跑）
        assertTrue(items.any { it.kind == ConversationItemKind.USER }, "INVALID：fixture 里没有 USER 条目")
    }

    @Test
    fun preFixRootCauseShapeIsGone() {
        val items = projectedItems()
        assertFixtureShape(items)   // ← 没有这条，fixture 解析退化时下面的 0 依然绿（假绿）

        // 判据 3（负对照）：修复前这批记录落成「CONTEXT 且正文带转发前缀」，现在是 0。
        // 这条同时证明「上面那些 ASSISTANT 不是从别处冒出来的」。
        assertEquals(0, items.count {
            it.kind == ConversationItemKind.CONTEXT && it.text.startsWith("[Group post from")
        }, "修复后不应再有 CONTEXT+转发前缀 的旧形态")
    }

    // ------------------------------------------------------------------
    // 下面两条是**构造数据**：真实 fixture 里这两种形态各 0 条（AndroidTester 查实），
    // 只跑真数据的话这两条分支等于零覆盖。
    // ------------------------------------------------------------------

    private fun syntheticRelayPost(seq: Int, extraSource: String, withName: Boolean): String {
        val name = if (withName) """ "senderName": "AndroidTester",""" else ""
        return """{"type":"user/message","seq":$seq,"time":1793578891953,"data":{"content":[{"type":"text","text":"[Group post from X]\n合成发言 $seq"}],"source":{"kind":"bot","role":"post","form":"relay","roomId":"$sessionId",$name"senderSessionId":"bot-x"$extraSource}}}"""
    }

    @Test
    fun systemEventsStayContextAndNeverCarryAName() {
        // 系统事件（failed / resumed / done）**不得**被当成 Bot 发言：落 CONTEXT 且 senderName == null
        val jsonl = listOf(
            syntheticRelayPost(91001, """, "failed": true""", withName = true),
            syntheticRelayPost(91002, """, "resumed": true""", withName = true),
            syntheticRelayPost(91003, """, "done": true""", withName = true),
            syntheticRelayPost(91004, "", withName = true)   // 对照：不带系统键的那条必须变成 ASSISTANT
        ).joinToString("\n")

        val items = project(recordsOf(jsonl, sessionId))
        val assistantNamed = items.filter { it.kind == ConversationItemKind.ASSISTANT && it.senderName != null }
        val contexts = items.filter { it.kind == ConversationItemKind.CONTEXT }

        assertEquals(1, assistantNamed.size, "只有不带系统键的那条合成发言应成为 ASSISTANT+senderName")
        assertEquals(3, contexts.size, "failed/resumed/done 三条必须仍是 CONTEXT（系统提示）")
        assertEquals(0, contexts.count { it.senderName != null }, "系统提示**绝不能**带发言人名字")
        // 非空自证：三条被排除的记录确实被投影读到了（不是"一条都没进来所以没有名字"）
        assertTrue(contexts.all { it.text.contains("合成发言") }, "被排除的三条应仍以 CONTEXT 形态可见")
    }

    @Test
    fun botPostWithoutSenderNameKeepsAssistantButShowsNoName() {
        // 缺 senderName 的 relay post（网关/source 未给名字）⇒ 仍进 ASSISTANT，但 **senderName == null**。
        // 这条是拆穿「名字其实来自别处」的唯一形态：只要渲染层自己找名字，它就会显示出来。
        val jsonl = syntheticRelayPost(92001, "", withName = false)
        val items = project(recordsOf(jsonl, sessionId))

        val assistant = items.filter { it.kind == ConversationItemKind.ASSISTANT }
        assertEquals(1, assistant.size, "缺名字的 relay post 仍应是 ASSISTANT（不能整条丢掉）")
        assertEquals(null, assistant.single().senderName, "服务端没给名字 ⇒ KMP 必须产出 null（渲染层不得回落）")
        assertTrue(assistant.single().text.contains("合成发言 92001"), "正文应保留")
    }
}