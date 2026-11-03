package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMobileFacade
import com.clarklevis.dsh.shared.facade.SharedMobileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMobileStoreTest {
    @Test
    fun facadeNormalizesWireKindForPlatformAdapters() {
        assertEquals(
            "event",
            SharedMobileFacade().decodeFrameKind(GatewayProtocolFixtures.LIVE_EVENT_WITHOUT_KIND)
        )
    }

    @Test
    fun manualFixtureUsesSharedReducersAndProjection() {
        val store = SharedMobileStore()
        val snapshot = store.loadManualTestFixture()
        assertEquals("android-demo", snapshot.selectedSessionId)
        assertEquals(1, snapshot.sessions.size)
        assertTrue(snapshot.sessions.single().isRunning)
        assertEquals(2, snapshot.conversation.size)
        assertEquals("共享协议解码、Reducer 与投影已接入。", snapshot.conversation.last().text)
        assertEquals(1, snapshot.pendingQuestionCount)
        assertEquals("question-requested", snapshot.lastFrameKind)
        assertNull(snapshot.lastError)
    }

    @Test
    fun invalidFrameIsReportedWithoutDestroyingCurrentState() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame("not-json")
        assertEquals(1, snapshot.sessions.size)
        assertTrue(!snapshot.lastError.isNullOrBlank())
    }

    @Test
    fun historyAndStreamCreateSessionsWithReliableTimestamps() {
        val store = SharedMobileStore(nowEpochSeconds = { 1_800_000_000.0 })

        var snapshot = store.acceptFrame(
            """{"kind":"history","sessionId":"empty-history","events":[]}"""
        )
        assertEquals(1_800_000_000.0, snapshot.sessions.single().lastActivityEpochSeconds)

        snapshot = store.acceptFrame(
            """{"kind":"event","sessionId":"stream","seq":1,"time":1800000001000,"event":{"type":"assistant/chunk","text":"delta"}}"""
        )
        assertEquals(
            1_800_000_001.0,
            snapshot.sessions.first { it.id == "stream" }.lastActivityEpochSeconds
        )
    }

    @Test
    fun sentResponseSelectsTheServerAssignedSessionForImmediateLiveProjection() {
        val store = SharedMobileStore(nowEpochSeconds = { 1_800_000_000.0 })

        var snapshot = store.acceptFrame(
            """{"kind":"sent","sessionId":"session-new"}"""
        )
        assertEquals("session-new", snapshot.selectedSessionId)
        assertEquals("session-new", snapshot.sessions.single().id)

        snapshot = store.acceptFrame(
            """{"kind":"event","sessionId":"session-new","seq":1,"time":1800000001,"event":{"type":"user/message","text":"第一句话"}}"""
        )
        assertEquals(listOf("第一句话"), snapshot.conversation.map { it.text })
    }

    @Test
    fun approvalLifecycleProducesSharedEffectAndWaitsForResolution() {
        val store = SharedMobileStore()
        store.acceptFrame(
            """{"kind":"event","sessionId":"s1","seq":1,"time":1,"event":{"type":"tool/call","callId":"call-1","name":"Bash","arguments":"{\"command\":\"sw_vers && uname -a\",\"description\":\"读取系统版本\"}"}}"""
        )
        var snapshot = store.acceptFrame(GatewayProtocolFixtures.REPLAYED_APPROVAL_REQUEST)
        assertEquals(1, snapshot.pendingApprovalCount)
        assertEquals("Bash", snapshot.pendingApprovals.single().toolName)
        assertEquals("idle", snapshot.approvalRequestStatuses["rpc-approval-1"]?.kind)
        assertEquals(
            "sw_vers && uname -a",
            snapshot.approvalCommandPreviews["rpc-approval-1"]
        )
        assertEquals(
            "读取系统版本",
            snapshot.approvalDetails["rpc-approval-1"]?.get("description")?.stringValue
        )

        val submission = store.submitApprovalDecision(
            rpcId = "rpc-approval-1",
            outcome = "allowed-once",
            isConnected = true
        )
        assertEquals("respond", submission.effect?.action)
        assertEquals("approval-1", submission.effect?.approvalId)
        assertEquals("allowed-once", submission.effect?.outcome)
        assertEquals(
            "submitting",
            submission.snapshot.approvalRequestStatuses["rpc-approval-1"]?.kind
        )

        snapshot = store.acceptFrame(
            """{"kind":"approval-response","rpcId":"rpc-approval-1","sessionId":"s1","approvalId":"approval-1","outcome":"allowed-once","accepted":true}"""
        )
        assertEquals("accepted", snapshot.approvalRequestStatuses["rpc-approval-1"]?.kind)
        snapshot = store.acceptFrame(
            """{"kind":"approval-resolved","rpcId":"rpc-approval-1","sessionId":"s1","approvalId":"approval-1","outcome":"allowed-once"}"""
        )
        assertEquals(0, snapshot.pendingApprovalCount)
        assertNull(snapshot.approvalRequestStatuses["rpc-approval-1"])
    }

    @Test
    fun productFramesPopulateWorkspaceSearchAndControlUiState() {
        val store = SharedMobileStore()
        var snapshot = store.acceptFrame(
            """{"kind":"workspaces","items":[{"workspaceId":"w1","path":"/work","title":"Work","sessionIds":["s1"],"createdAt":"now","updatedAt":"now"}]}"""
        )
        assertEquals("Work", snapshot.workspaces.single().title)

        snapshot = store.acceptFrame(
            """{"kind":"search","items":[{"sessionId":"s1","snippet":"match"}]}"""
        )
        assertEquals(listOf("s1"), snapshot.searchResultSessionIds)

        snapshot = store.acceptFrame(
            """{"kind":"agent-presets","agentPresetDefault":"standard","presets":[{"id":"standard","isDefault":true,"name":"Standard"}]}"""
        )
        assertEquals("standard", snapshot.agentPresetDefault)
        assertEquals("Standard", snapshot.agentPresets.single().name)

        snapshot = store.acceptFrame(
            """{"kind":"set-default","target":"agent-preset","value":"cordis","applied":true}"""
        )
        assertEquals("cordis", snapshot.agentPresetDefault)

        snapshot = store.acceptFrame(
            """{"kind":"set-default","target":"permission","value":"workspace-write","applied":true}"""
        )
        assertEquals("workspace-write", snapshot.permissionDefault)

        snapshot = store.acceptFrame(
            """{"kind":"models","current":{"provider":"deepseek","model":"deepseek-chat"},"routable":true,"groups":[{"id":"deepseek","name":"DeepSeek","models":[{"id":"deepseek-chat","name":"DeepSeek Chat"}]}]}"""
        )
        assertEquals("deepseek-chat", snapshot.modelCatalog?.current?.model)

        snapshot = store.acceptFrame(
            """{"kind":"session-stats","asOfSeq":4,"sessionStats":{"turns":2,"steps":3},"contextPressure":{"pressureTokens":100,"contextWindow":1000}}"""
        )
        assertEquals(2, snapshot.statsSnapshot?.stats?.turns)
        assertEquals(1_000, snapshot.statsSnapshot?.contextPressure?.contextWindow)

        snapshot = store.acceptFrame(
            """{"kind":"host","version":"0.1.11","cwd":"/work","provider":"deepseek-official","model":"deepseek-v4","attachedSessions":12,"canOpenPath":true}"""
        )
        assertEquals("0.1.11", snapshot.hostSnapshot?.version)
        assertEquals("deepseek-official", snapshot.hostSnapshot?.provider)
        assertEquals(12, snapshot.hostSnapshot?.attachedSessions)
    }

    @Test
    fun sessionStatsAreKeptPerSessionForNonSelectedSessions() {
        val store = SharedMobileStore()
        store.selectSession("s1")

        // 选中会话 s1 的 stats：既进 statsSnapshot，也进按会话表
        store.acceptFrame(
            """{"kind":"session-stats","sessionId":"s1","asOfSeq":5,"sessionStats":{"turns":2,"steps":3}}"""
        )
        // 非选中会话 s2 的 stats：只进按会话表（首页运行中会话卡要用它）
        store.acceptFrame(
            """{"kind":"session-stats","sessionId":"s2","asOfSeq":7,"sessionStats":{"turns":9,"steps":11}}"""
        )

        val snapshot = store.snapshot()
        assertEquals(2, snapshot.statsSnapshot?.stats?.turns)
        assertEquals(5L, snapshot.statsBySessionSnapshot["s1"]?.asOfSeq)
        assertEquals(9, snapshot.statsBySessionSnapshot["s2"]?.stats?.turns)
        assertEquals(11, snapshot.statsBySessionSnapshot["s2"]?.stats?.steps)

        // asOfSeq 单调：乱序推来的旧帧不覆盖新值（与 tasks/goals 同一条判据）
        store.acceptFrame(
            """{"kind":"session-stats","sessionId":"s2","asOfSeq":4,"sessionStats":{"turns":1,"steps":1}}"""
        )
        assertEquals(9, store.snapshot().statsBySessionSnapshot["s2"]?.stats?.turns)

        // projection-baseline 清空按会话表（重新同步起点）
        store.acceptFrame("""{"kind":"projection-baseline","projections":{}}""")
        assertEquals(0, store.snapshot().statsBySessionSnapshot.size)
    }

    @Test
    fun taskAndGoalProjectionsUseAsOfSequenceToRejectOutOfOrderPushes() {
        val store = SharedMobileStore()
        store.selectSession("s1")

        var snapshot = store.acceptFrame(
            """{"kind":"tasks","sessionId":"s1","asOfSeq":12,"todos":[{"content":"检查 SDK","status":"completed"},{"content":"构建 APK","status":"in_progress"}]}"""
        )
        assertEquals(listOf("completed", "in_progress"), snapshot.taskSnapshot?.tasks?.map { it.status })

        snapshot = store.acceptFrame(
            """{"kind":"tasks-updated","sessionId":"s1","asOfSeq":11,"todos":[{"content":"过期任务","status":"pending"}]}"""
        )
        assertEquals("检查 SDK", snapshot.taskSnapshot?.tasks?.first()?.content)

        snapshot = store.acceptFrame(
            """{"kind":"goal","sessionId":"s1","asOfSeq":12,"goal":{"goal":{"id":"goal-1","revision":7,"objective":"初始化 Android app","phase":"active","maxGoalRounds":12},"roundsStarted":3}}"""
        )
        assertEquals("goal-1", snapshot.goalSnapshot?.goal?.goal?.id)
        assertEquals(7, snapshot.goalSnapshot?.goal?.goal?.revision)

        snapshot = store.acceptFrame(
            """{"kind":"goal-updated","sessionId":"s1","asOfSeq":13,"goal":null}"""
        )
        assertNull(snapshot.goalSnapshot?.goal)
    }

    /**
     * 76④（2026-11-03 用户报「答完问题卡片一直挂着」）：宿主重启/广播过滤会让
     * `question-resolved`/`approval-resolved` 永远到不了客户端。对账机制 =
     * 网关 replay 完整注册表后发 `interaction-replay-end`，客户端把
     * 「本机有、replay 里没有」的 pending 就地清掉。
     *
     * Chief 三判据：
     * ① replay 后本机有、replay 里没有 → 清
     * ② **replay 未到时一张卡不清**（红线——网络抖动/延迟绝不能误清还没处理的问题卡）
     * ③ approval 同族一起治
     */
    @Test
    fun interactionReplayEndReconcilesStalePendingCards() {
        val store = SharedMobileStore()
        val questionA = """{"kind":"question-requested","rpcId":"q-stale","sessionId":"s1","replay":false,"questions":[{"id":"x","question":"过期问题","options":[{"label":"a"}]}]}"""
        val questionB = """{"kind":"question-requested","rpcId":"q-alive","sessionId":"s1","replay":false,"questions":[{"id":"x","question":"活着的问题","options":[{"label":"a"}]}]}"""
        store.acceptFrame(questionA)
        store.acceptFrame(questionB)
        store.acceptFrame("""{"kind":"approval-requested","rpcId":"appr-stale","sessionId":"s1","approvalId":"a1","toolName":"Bash","callId":"call-1","reason":"r","replay":false}""")
        var snapshot = store.acceptFrame("""{"kind":"approval-requested","rpcId":"appr-alive","sessionId":"s1","approvalId":"a2","toolName":"Bash","callId":"call-2","reason":"r","replay":false}""")
        assertEquals(2, snapshot.pendingQuestionCount)
        assertEquals(2, snapshot.pendingApprovalCount)

        // **红线（判据②）**：replay 没到（没有任何 replay:true 帧），此时到达的 end 帧
        // **一张卡都不清**——绝不能因对账误删还没处理的问题卡。
        snapshot = store.acceptFrame("""{"kind":"interaction-replay-end"}""")
        assertEquals(2, snapshot.pendingQuestionCount, "replay 未到 ⇒ 不许对账（红线）")
        assertEquals(2, snapshot.pendingApprovalCount, "replay 未到 ⇒ 不许对账（红线）")

        // 判据①③：replay 只包含 q-alive / appr-alive（宿主注册表的真实现状），
        // end 帧到达 ⇒ 不在 replay 里的 q-stale / appr-stale 被清，活着的保留。
        store.acceptFrame(questionB.replace("\"replay\":false", "\"replay\":true"))
        store.acceptFrame("""{"kind":"approval-requested","rpcId":"appr-alive","sessionId":"s1","approvalId":"a2","toolName":"Bash","callId":"call-2","reason":"r","replay":true}""")
        snapshot = store.acceptFrame("""{"kind":"interaction-replay-end"}""")
        assertEquals(listOf("q-alive"), snapshot.pendingQuestions.map { it.rpcId }, "replay 里没有的过期问题卡必须被清")
        assertEquals(listOf("appr-alive"), snapshot.pendingApprovals.map { it.rpcId }, "approval 同族一起对账")
        assertNull(snapshot.lastError)
    }
}
