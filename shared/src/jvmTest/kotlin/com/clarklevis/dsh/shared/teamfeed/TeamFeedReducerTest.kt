package com.clarklevis.dsh.shared.teamfeed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 段 2 的判据用例（计划文档「段 2 · 怎么验」逐条落地）。
 * 帧样本形状以网关 `team-feed.mjs` 为准（2026-11-03 实读后手写），负例必红。
 */
class TeamFeedReducerTest {

    private val parser = Json { ignoreUnknownKeys = true }

    private fun snapshot(seq: Long = 7, degraded: Boolean = false, bots: String = defaultBot): TeamFeedFrame.Snapshot =
        parseTeamFeedFrame(
            """{"v":1,"kind":"snapshot","instanceId":"inst-1","seq":$seq,"at":100,"degraded":$degraded,
               "mainBotId":"b1","mainBotIds":["b1"],
               "bots":[$bots],"rooms":[],"pending":[]}""",
        ) as TeamFeedFrame.Snapshot

    private val defaultBot =
        """{"id":"b1","kind":"bot","name":"iOSCoder","role":"DEV","sessionId":"session-b1",
           "main":true,"pinned":false,"activity":"working","avatar":{"shape":"whale"},"color":"deepseek"}"""

    private fun delta(seq: Long, base: Long, ops: String, degraded: Boolean = false): TeamFeedFrame.Delta =
        parseTeamFeedFrame(
            """{"v":1,"kind":"delta","instanceId":"inst-1","seq":$seq,"base":$base,"at":101,
               "degraded":$degraded,"ops":[$ops]}""",
        ) as TeamFeedFrame.Delta

    // —— 段 2 判据 1：快照替换 + seq 推进 ——
    @Test
    fun snapshotReplacesRowsAndAdvancesSeq() {
        val reducer = TeamFeedReducer()
        val outcome = reducer.apply(snapshot(seq = 7))
        assertTrue(outcome is TeamFeedReducer.Outcome.Applied)
        assertEquals(7L, reducer.state.seq)
        assertEquals(1, reducer.state.rows.size)
        assertEquals(reducer.state.rows.getValue("b1").name, "iOSCoder")
        assertTrue(reducer.state.rows.getValue("b1").main)
    }

    // —— 段 2 判据 2：delta.base != 本地 seq ⇒ 丢弃 + 请求重取快照（状态不动）——
    @Test
    fun deltaWithWrongBaseIsDroppedAndRequestsSnapshot() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        val before = reducer.state
        val outcome = reducer.apply(delta(seq = 9, base = 6, ops = """{"op":"bot.patch","id":"b1","set":{"activity":"idle"}}"""))
        assertTrue(outcome is TeamFeedReducer.Outcome.NeedSnapshot)
        assertTrue(reducer.needSnapshot)
        assertEquals(before, reducer.state, "丢弃后状态不能动")
    }

    @Test
    fun deltaWithMatchingBaseAppliesAndAdvancesSeq() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        val outcome = reducer.apply(
            delta(seq = 8, base = 7, ops = """{"op":"bot.patch","id":"b1","set":{"activity":"idle"}}"""),
        )
        assertTrue(outcome is TeamFeedReducer.Outcome.Applied)
        assertEquals(8L, reducer.state.seq)
        assertEquals(reducer.state.rows.getValue("b1").activity, "idle")
    }

    // —— 段 2 判据 3：patch 裁静态字段（契约 §4；客户端不信任上游裁剪）——
    @Test
    fun patchCannotTouchStaticFields() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        reducer.apply(
            delta(
                seq = 8, base = 7,
                ops = """{"op":"bot.patch","id":"b1","set":{"name":"冒名","avatar":{"shape":"zed"},"activity":"thinking"}}""",
            ),
        )
        val row = reducer.state.rows.getValue("b1")
        assertEquals("iOSCoder", row.name, "静态 name 不许被 patch 改")
        assertEquals("whale", row.avatar.shape, "静态 avatar 不许被 patch 改")
        assertEquals("thinking", row.activity, "动态 activity 正常生效")
    }

    // —— 段 2 判据 4：结构 op（add/update/remove）+ main.set ——
    @Test
    fun structuralOpsUpsertRemoveAndMainSet() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        reducer.apply(
            delta(
                seq = 9, base = 7,
                ops = """{"op":"bot.add","id":"b2","entity":{"id":"b2","kind":"bot","name":"Coder","sessionId":"session-b2"}},
                         {"op":"main.set","mainBotIds":["b2"]},
                         {"op":"bot.remove","id":"b1"}""",
            ),
        )
        assertEquals(listOf("b2"), reducer.state.mainBotIds)
        assertEquals(setOf("b2"), reducer.state.rows.keys)
    }

    /** 没有 sessionId 的 bot 行必须被丢（契约：bot 必带 sessionId，fallbackSnapshot :352 同规）。 */
    @Test
    fun botWithoutSessionIdIsRejected() {
        val frame = parser.parseToJsonElement(
            """{"v":1,"kind":"snapshot","instanceId":"i","seq":1,"degraded":false,"mainBotIds":[],
               "bots":[{"id":"x","kind":"bot","name":"无会话"}],"rooms":[],"pending":[]}""",
        ).jsonObject.let(::parseTeamFeedFrame) as TeamFeedFrame.Snapshot
        assertTrue(frame.rows.isEmpty(), "无 sessionId 的 bot 必须丢弃")
    }

    // —— 段 2 判据 5：order 帧不推进消费水位（seq 是 head 回显）——
    @Test
    fun orderFrameDoesNotAdvanceSeq() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        val outcome = reducer.apply(
            parseTeamFeedFrame(
                """{"v":1,"kind":"order","instanceId":"inst-1","seq":7,"orderSeq":3,"at":102,
                   "sessions":["s2","s1"],"degraded":false}""",
            ) as TeamFeedFrame.Order,
        )
        assertTrue(outcome is TeamFeedReducer.Outcome.Applied)
        assertEquals(7L, reducer.state.seq, "order 不推进 seq（否则下一次 delta.base 立即失配）")
        assertEquals(listOf("s2", "s1"), reducer.state.orderSessions)

        // 假绿修复（AndroidTester 2026-11-03）：本例 seq 与 order 帧回显值恰好相等，
        // 若实现错写成 `seq = frame.seq` 不会红——必须跟一条 delta(base=seq) 断言 Applied，
        // 才把「order 不推进消费水位」这个不变式真正钉死。
        val followUp = reducer.apply(
            delta(seq = 8, base = 7, ops = """{"op":"bot.patch","id":"b1","set":{"activity":"idle"}}"""),
        )
        assertTrue(followUp is TeamFeedReducer.Outcome.Applied, "order 之后 base=seq 的 delta 必须照常生效")
        assertEquals(8L, reducer.state.seq)
        assertEquals("idle", reducer.state.rows.getValue("b1").activity)
    }

    // —— 段 2 判据 6：degraded 每帧必带，且显式置位（不许渲染成全部空闲）——
    @Test
    fun degradedFlagIsExplicitOnEveryFrame() {
        val reducer = TeamFeedReducer()
        // 降级快照的形状照 `fallbackSnapshot`（team-feed.mjs:352-371）：只有名册，刻意不写 activity——
        // 契约枚举里没有 unknown，写 idle 就是谎报；缺字段 + degraded:true 才是诚实表达。
        val frame = parseTeamFeedFrame(
            """{"v":1,"kind":"snapshot","instanceId":"inst-1","seq":7,"at":100,"degraded":true,
               "mainBotId":"b1","mainBotIds":["b1"],
               "bots":[{"id":"b1","kind":"bot","name":"iOSCoder","role":"DEV","sessionId":"session-b1",
                        "work":{},"approvals":[],"pending":[],"waiting":{"bots":[],"rooms":[],"for":[]}}],
               "rooms":[],"pending":[]}""",
        ) as TeamFeedFrame.Snapshot
        reducer.apply(frame)
        assertTrue(reducer.state.degraded)
        val row = reducer.state.rows.getValue("b1")
        assertEquals(null, row.activity)
    }

    // —— 段 2 判据 7：卡片增量 op；answered/cleared 不带正文，靠活卡集合退役 ——
    @Test
    fun cardOpsMaintainLiveCards() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        reducer.apply(
            parseTeamFeedFrame(
                """{"v":2,"kind":"card","instanceId":"inst-1","seq":7,"base":6,"at":103,
                   "ops":[{"op":"card.shown","id":"c1","card":{"id":"c1","roomId":"room-1","kind":"question","prompt":"等你回答","options":["a","b"]}},
                          {"op":"card.shown","id":"c2","card":{"id":"c2","botId":"b1","kind":"view","title":"方案对比"}}]}""",
            ) as TeamFeedFrame.Cards,
        )
        assertEquals(2, reducer.state.cards.size)
        assertEquals(reducer.state.cards.getValue("c1").prompt, "等你回答")

        // answered/cleared 只带 id：退役必须靠 id，不能要求正文在场。
        reducer.apply(
            parseTeamFeedFrame(
                """{"v":2,"kind":"card","instanceId":"inst-1","seq":8,"base":7,"at":104,
                   "ops":[{"op":"card.answered","id":"c1"},{"op":"card.cleared","id":"c2"}]}""",
            ) as TeamFeedFrame.Cards,
        )
        assertTrue(reducer.state.cards.isEmpty(), "answered 后必须退役（iOS :139-147 的真机坑）")
    }

    @Test
    fun cardSnapshotReplacesWholeSet() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        reducer.apply(
            parseTeamFeedFrame(
                """{"v":2,"kind":"card","instanceId":"inst-1","seq":8,"base":7,"at":105,
                   "ops":[{"op":"card.shown","id":"c1","card":{"id":"c1","botId":"b1","kind":"question"}}]}""",
            ) as TeamFeedFrame.Cards,
        )
        reducer.apply(
            parseTeamFeedFrame(
                """{"v":2,"kind":"card-snapshot","instanceId":"inst-1","seq":9,"at":106,
                   "cards":[{"id":"c9","roomId":"room-1","kind":"secret","name":"密钥"}]}""",
            ) as TeamFeedFrame.Cards,
        )
        assertEquals(setOf("c9"), reducer.state.cards.keys)
    }

    // —— 段 2 判据 8：instanceId 变更 ⇒ 丢弃 + 重取快照（host 重启）——
    @Test
    fun instanceIdChangeRequestsSnapshot() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        val outcome = reducer.apply(
            parseTeamFeedFrame(
                """{"v":1,"kind":"delta","instanceId":"inst-2","seq":2,"base":7,"at":106,"degraded":false,
                   "ops":[{"op":"bot.patch","id":"b1","set":{"activity":"idle"}}]}""",
            ) as TeamFeedFrame.Delta,
        )
        assertTrue(outcome is TeamFeedReducer.Outcome.NeedSnapshot)
        assertTrue(reducer.needSnapshot)
    }

    // —— 段 2 判据 9：avatar 补齐批次只合并既有行 ——
    @Test
    fun avatarBatchMergesIntoExistingRows() {
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        reducer.apply(
            parseTeamFeedFrame(
                """{"v":1,"kind":"avatar","items":[{"id":"b1","avatar":{"shape":"star","image":"data:image/png;base64,AAA"}}]}""",
            ) as TeamFeedFrame.AvatarBatch,
        )
        val avatar = reducer.state.rows.getValue("b1").avatar
        assertEquals(avatar.shape, "star")
        assertEquals(avatar.image, "data:image/png;base64,AAA")
    }

    /** 缺口 1 的 shared 侧用例：feed 帧生产路径（TeamFeedFrames:280）读 tilt，
     *  之前只有 androidApp 解析器那份有用例——两份实现共存期间各自都要有判据（段 5 收口）。 */
    @Test
    fun sharedGlyphPathParsesFifthEyeElementAsTilt() {
        val frame = parseTeamFeedFrame(
            """{"v":1,"kind":"snapshot","instanceId":"i","seq":1,"degraded":false,"mainBotIds":[],
               "bots":[{"id":"b1","kind":"bot","name":"n","sessionId":"s1",
                        "glyph":{"size":100,"path":"M1 1L2 2Z",
                                 "eyes":[[10,10,4,2,-15.8],[10,30,4,2,-21.2]]}}],
               "rooms":[],"pending":[]}""",
        ) as TeamFeedFrame.Snapshot
        // Snapshot.rows 是 List（原始帧），不是 reducer 归并后的 Map。
        val eyes = frame.rows.first { it.id == "b1" }.glyph!!.eyes
        assertEquals(-15.8, eyes[0].tilt, 1e-9)
        assertEquals(-21.2, eyes[1].tilt, 1e-9)
    }

    // —— vc59 红用例：握手帧是 {type:"ready", kind:"team"}（server.mjs:570），KMP 曾只按 kind
    //    分发，kind="team" 落 else → null → phase 永远到不了 LIVE（vc56/57/58 三版未闭合的根因）——
    @Test
    fun readyHandshakeFrameIsRecognizedViaType() {
        val frame = parseTeamFeedFrame(
            """{"type":"ready","kind":"team","serverId":"srv-1","instanceId":"inst-1","seq":5,
               "cards":true,"degraded":{"team":false,"cards":false}}""",
        )
        assertTrue(frame is TeamFeedFrame.Ready, "网关握手帧按 type 分发必须认成 Ready（vc58 前此处返回 null）")
    }

    // —— vc59 判据 2 反向对照：type 先行分发不得伤及 kind 系帧，snapshot/delta/order/card 照旧 ——
    @Test
    fun kindFramesStillParseAfterTypeFirstDispatch() {
        assertTrue(
            parseTeamFeedFrame(
                """{"v":1,"kind":"snapshot","instanceId":"i","seq":1,"degraded":false,"mainBotIds":[],
                   "bots":[$defaultBot],"rooms":[],"pending":[]}""",
            ) is TeamFeedFrame.Snapshot,
        )
        assertTrue(
            parseTeamFeedFrame(
                """{"v":1,"kind":"delta","instanceId":"i","seq":2,"base":1,"degraded":false,
                   "ops":[{"op":"bot.patch","id":"b1","set":{"activity":"idle"}}]}""",
            ) is TeamFeedFrame.Delta,
        )
        assertTrue(
            parseTeamFeedFrame(
                """{"v":1,"kind":"order","instanceId":"i","seq":1,"orderSeq":1,"degraded":false,"sessions":["s1"]}""",
            ) is TeamFeedFrame.Order,
        )
        assertTrue(
            parseTeamFeedFrame(
                """{"v":2,"kind":"card-snapshot","instanceId":"i","seq":3,"cards":[]}""",
            ) is TeamFeedFrame.Cards,
        )
    }

    // —— type 在场但认不出：iOS 同款吞掉、不落 kind 分支（pong 随心跳批、sync.done 随 resume 批）——
    @Test
    fun unknownTypeFramesAreSwallowedNotKindDispatched() {
        assertEquals(null, parseTeamFeedFrame("""{"type":"mystery","kind":"snapshot"}"""))
        assertEquals(null, parseTeamFeedFrame("""{"type":"pong","at":123}"""))
    }

    // —— error 帧（replyControl）：解析出 code/message 供日志留痕，不许静默 ——
    @Test
    fun errorFrameCarriesCodeAndMessage() {
        val frame = parseTeamFeedFrame(
            """{"type":"error","error":{"code":"SYNC_GAP","message":"The resume window does not reach back that far","details":{}}}""",
        ) as TeamFeedFrame.Error
        assertEquals("SYNC_GAP", frame.code)
        assertEquals("The resume window does not reach back that far", frame.message)
    }

    // —— 负例：未知 kind / 不合规 op 整帧丢弃，不许混进状态 ——
    @Test
    fun unknownKindAndIllegalOpsAreDropped() {
        assertEquals(null, parseTeamFeedFrame("""{"v":1,"kind":"mystery"}"""))
        val reducer = TeamFeedReducer()
        reducer.apply(snapshot(seq = 7))
        // op 缺 set / entity ⇒ normalizeOp 返回 null ⇒ 该 op 被丢，帧其余部分照常。
        val outcome = reducer.apply(
            delta(seq = 8, base = 7, ops = """{"op":"bot.rename","id":"b1"},{"op":"bot.patch","id":"b1","set":{"activity":"idle"}}"""),
        )
        assertTrue(outcome is TeamFeedReducer.Outcome.Applied)
        assertEquals(reducer.state.rows.getValue("b1").activity, "idle")
    }
}
