package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMobileStore
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 群聊成员发言的**实时（wire）形态**回归（2026-11-02）。
 *
 * 为什么需要这份：`RoomRelaySenderNameTest` 的全部用例喂的都是**历史（log）形态**
 * （`{type,seq,time,data}`，经 `RawSessionEvent.normalized()` ⇒ `raw = 完整 data`）。
 * 而实时走的是另一条入口（`SharedMobileStore.kt:449-462`）：
 * ```
 * kind:"event" ⇒ acceptEvent(SessionEvent(sessionId, seq, time, frame.event))
 * ⇒ GatewayEvent.raw = 帧里 event.raw 的原值，**不做任何补全**
 * ```
 * 两条输入形态不同 ⇒ 同一条 relay post 在历史里能带名字、在实时里可能落成 CONTEXT。
 * **「只测了历史、以为覆盖了群聊」的假绿是今晚反复出现的形态**，所以单独立一份。
 *
 * 判据分两档，互不重叠：
 * 1. 帧里 `raw.source` 齐全（= 宿主已装网关 `lib/index.mjs:357-359` 的产形）⇒ ASSISTANT + 名字
 *    ⇒ 证明「网关对了，两端共用的这一层就对了」，客户端不需要为名字再做任何事。
 * 2. 帧里 `raw` 只有 `{id}`（= 旧副本的产形）⇒ CONTEXT 且**无名字**
 *    ⇒ 客户端不会凭空造名字（与「缺名不发 author、假身份比空更糟」同一口径）。
 */
class RoomRelayLiveWireTest {
    private val roomId = "session-2a10aef5-2e31-4551-8af6-880da873f4d8"

    /** 一条实时帧。`rawSource` 传 null = 模拟「raw 里没有 source」的旧形态。 */
    private fun liveFrame(seq: Int, rawSource: String?, sourceKind: String? = "bot"): String {
        val sourceField = if (sourceKind == null) "" else "\"source\":\"$sourceKind\","
        val raw = if (rawSource == null) """{"id":"m-$seq"}""" else """{"id":"m-$seq","source":$rawSource}"""
        val event = """{"type":"user/message","text":"[Group post from X]\n实时发言 $seq",$sourceField"raw":$raw}"""
        return """{"kind":"event","sessionId":"$roomId","seq":$seq,"time":$seq,"event":$event}"""
    }

    private fun relaySource(name: String? = "ReleaseBot"): String {
        val nameField = if (name == null) "" else ",\"senderName\":\"$name\""
        return """{"kind":"bot","role":"post","form":"relay","senderSessionId":"bot-x"$nameField}"""
    }

    /**
     * 走**实时入口**（`kind:"event"`）投影。
     * 先发一条 `sent` 让这个会话成为当前会话：`snapshot.conversation` 只暴露选中会话，
     * 否则会得到一条"空列表 ⇒ 断言全绿"的假通过。
     */
    private fun projectLive(vararg frames: String): List<ConversationItem> {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"sent","sessionId":"$roomId"}""")
        var snapshot = store.snapshot()
        frames.forEach { frame -> snapshot = store.acceptFrame(frame) }
        return snapshot.conversation
    }

    @Test
    fun liveWireFrameWithFullSourceBecomesNamedAssistant() {
        // 非空自证放在最前：万一帧根本没被收下，下面的断言会变成"空集通过"
        val items = projectLive(liveFrame(seq = 1, rawSource = relaySource()))
        assertTrue(items.isNotEmpty(), "INVALID：实时帧没有产出任何条目 ⇒ 下面的断言不算数")

        val assistant = items.singleOrNull { it.kind == ConversationItemKind.ASSISTANT }
        assertNotNull(assistant, "raw.source 齐全时必须是 ASSISTANT，实际类目：" +
            items.joinToString { "${it.kind}" })
        assertEquals("ReleaseBot", assistant.senderName,
            "名字必须沿用服务端给的 source.senderName（客户端不推断）")
        assertTrue(assistant.text.contains("实时发言 1"), "正文应保留：${assistant.text}")
        assertTrue(!assistant.text.startsWith("[Group post from"),
            "转发前缀必须已剥离，否则气泡里会露出内部格式")
    }

    @Test
    fun liveWireFrameWithoutRawSourceStaysContextAndGetsNoName() {
        // 负对照：缺 raw.source ⇒ 客户端【不能】猜测身份。
        // 这条同时是「网关没同步」时的可观察形态：显示成无名上下文条目，而不是带错名字的气泡。
        val items = projectLive(liveFrame(seq = 2, rawSource = null))
        assertTrue(items.isNotEmpty(), "INVALID：实时帧没有产出任何条目")

        assertEquals(0, items.count { it.kind == ConversationItemKind.ASSISTANT },
            "缺 raw.source 时不得产出 ASSISTANT ⇒ 客户端没有在猜身份")
        assertEquals(0, items.count { it.senderName != null }, "缺 raw.source 时不得出现任何人名")
        val context = items.singleOrNull { it.kind == ConversationItemKind.CONTEXT }
        assertNotNull(context, "应落成 1 条 CONTEXT（可观察形态）：" + items.joinToString { "${it.kind}" })
        // **真机判读的机械区分点**（AndroidTester 2026-11-02 指出「出现但没名字」有两条来路）：
        //   CONTEXT 且保留 `[Group post from …]` 前缀 ⇒ 整帧没有 raw（该条 data.id 缺失）
        //   ASSISTANT 且无名字、前缀已剥离           ⇒ raw.source 在，只是 source 缺 senderName
        // 只有"前缀在不在"能分开这两条；不能靠"有没有名字"，因为无名 ASSISTANT 本身是正常形态。
        assertTrue(context.text.startsWith("[Group post from"),
            "整帧缺 raw 时才保留转发前缀；前缀不在说明这条不是②而是③的形态：${context.text}")
    }

    @Test
    fun relayWithoutSenderNameStaysAssistantButUnnamed() {
        // 服务端给了 relay 形态但没给名字 ⇒ 仍是 ASSISTANT，但 senderName 必须为 null（渲染层不得回落）。
        val items = projectLive(liveFrame(seq = 3, rawSource = relaySource(name = null)))
        val assistant = items.singleOrNull { it.kind == ConversationItemKind.ASSISTANT }
        assertNotNull(assistant, "缺名字的 relay 仍应是 ASSISTANT（不能整条丢掉）")
        assertEquals(null, assistant.senderName, "服务端没给名字 ⇒ KMP 必须产出 null")
    }
}
