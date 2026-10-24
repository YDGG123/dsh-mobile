package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMobileStore
import com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec
import com.clarklevis.dsh.shared.facade.SharedSessionListStore
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class SharedOfflineCatalogTest {
    @Test
    fun avatarAssignmentsRoundTripWithoutBecomingConversationTargets() {
        val assignments = """[{"leadSessionId":"s","sessionId":"a","index":37}]"""
        val cached = assertNotNull(codec.withTeamAvatars(catalog(), assignments))
        assertEquals(assignments, codec.teamAvatarsJson(assertNotNull(codec.normalize(cached))))
        assertEquals("[]", codec.conversationTargetsJson(cached))
        assertEquals("[]", codec.teamAvatarsJson(catalog().replace(",\"teamAvatars\":[]", "")), "旧目录应兼容缺少头像字段")
        assertNull(codec.withTeamAvatars(catalog(), assignments.replace("37", "-1")))
        assertNull(codec.withTeamAvatars(catalog(), assignments.dropLast(1) + "," + assignments.drop(1)))
    }

    private val codec = SharedOfflineCatalogCodec()
    private val list =
        """{"sessions":[{"id":"s","title":"缓存会话","lastActivityEpochSeconds":42,"isRunning":true,"hasUnread":true,"agentPreset":"code","hasConversation":true}],"archivedSessionIds":["archived"],"selectedSessionId":"s"}"""
    private val workspaces = """[{"workspaceId":"w","path":"/repo","title":"工作区","sessionIds":["s"],"createdAt":"1","updatedAt":"2"}]"""
    private fun catalog() = assertNotNull(codec.create(list, workspaces, "w"))

    @Test
    fun durableTargetsNormalizeReentryToRootWithoutRestoringStreamState() {
        val selectedChild = catalog().replace("\"selectedSessionId\":\"s\"", "\"selectedSessionId\":\"review\"")
        val targets = """[{"sessionId":"review","parentSessionId":"scout","subagentMode":"one-shot"},{"sessionId":"scout","parentSessionId":"s","subagentMode":"continuable"}]"""
        val cached = assertNotNull(codec.withConversationTargets(selectedChild, targets))
        val restored = SharedMobileStore().restoreOfflineCatalog(cached)
        assertEquals("s", restored.selectedSessionId)
        assertTrue(restored.conversation.isEmpty())
        assertNotNull(codec.normalize(cached))
        val routing = com.clarklevis.dsh.shared.facade.SharedTeamStore()
        assertTrue(routing.restoreRoutingJson(assertNotNull(codec.conversationTargetsJson(cached))))
        assertTrue(routing.isChild("review"))
        assertFalse(com.clarklevis.dsh.shared.facade.SharedTeamStore().isChild("review"))
        assertNull(codec.withConversationTargets(cached, """[{"sessionId":"x","parentSessionId":"y","subagentMode":"continuable"},{"sessionId":"y","parentSessionId":"x","subagentMode":"continuable"}]"""))
        assertNotNull(codec.normalize(catalog()), "旧目录没有地址字段时仍须兼容")
    }

    @Test
    fun canonicalDirectoryRoundTripsThroughBothPlatformFacadesWithoutHistorySideEffects() {
        val catalog = catalog()
        val ios = SharedSessionListStore("新建", "远程", "空白")
        assertTrue(ios.restore(assertNotNull(codec.sessionListJson(catalog))).isSuccess)
        val android = SharedMobileStore()
        val restored = android.restoreOfflineCatalog(catalog)
        assertEquals("s", restored.selectedSessionId)
        assertEquals("缓存会话", restored.sessions.single().title)
        assertTrue(restored.sessions.single().hasUnread)
        assertTrue(restored.sessions.single().isRunning)
        assertEquals("w", restored.workspaces.single().workspaceId)
        assertTrue(restored.conversation.isEmpty())
        assertEquals(0, restored.selectedHistoryLoadedEventCount)
        assertTrue(restored.pendingQuestions.isEmpty() && restored.pendingApprovals.isEmpty())
        assertNull(restored.lastFrameKind)
        assertEquals("w", codec.selectedWorkspaceId(catalog))
        assertEquals(codec.normalize(catalog), codec.normalize(android.exportOfflineCatalog("w")))
    }

    @Test
    fun realEmptyAuthorityReplacesOfflineDirectoryAndRejectsLateRestore() {
        val store = SharedMobileStore()
        store.restoreOfflineCatalog(catalog())
        assertTrue(store.acceptFrame("""{"kind":"sessions","items":[]}""").sessions.isEmpty())
        assertTrue(store.restoreOfflineCatalog(catalog()).sessions.isEmpty())
        assertTrue(store.acceptFrame("""{"kind":"workspaces","items":[]}""").workspaces.isEmpty())
    }

    @Test
    fun malformedMissingNullAndPartialSessionsNeverEraseDirectory() {
        val store = SharedMobileStore()
        store.restoreOfflineCatalog(catalog())
        for (frame in listOf(
            """{"kind":"hello"}""",
            """{"kind":"sessions"}""",
            """{"kind":"sessions","items":null}""",
            """{"kind":"sessions","items":{}}""",
            """{"kind":"sessions","items":[{}]}""",
            """{"kind":"sessions","items":[{"sessionId":"x"},{}]}""",
            """{"kind":"workspaces","items":[{}]}"""
        )) {
            val snapshot = store.acceptFrame(frame)
            assertEquals("s", snapshot.sessions.single().id, frame)
            assertEquals("w", snapshot.workspaces.single().workspaceId, frame)
        }
    }

    @Test
    fun authorityOrUserSelectionWinsOverLateDiskRestore() {
        val selected = SharedMobileStore()
        selected.selectSession("user-choice")
        assertEquals("user-choice", selected.restoreOfflineCatalog(catalog()).selectedSessionId)
        val fresh = SharedMobileStore()
        fresh.acceptFrame("""{"kind":"sessions","items":[]}""")
        val freshRestored = fresh.restoreOfflineCatalog(catalog())
        assertTrue(freshRestored.sessions.isEmpty())
        assertEquals("w", freshRestored.workspaces.single().workspaceId)
        val workspaceFresh = SharedMobileStore()
        workspaceFresh.acceptFrame("""{"kind":"workspaces","items":[]}""")
        val workspaceRestored = workspaceFresh.restoreOfflineCatalog(catalog())
        assertTrue(workspaceRestored.workspaces.isEmpty())
        assertEquals("s", workspaceRestored.sessions.single().id)
    }

    @Test
    fun partialAuthorityOnlyBlocksItsOwnDirectoryAndPreservesUserSelection() {
        val workspaceFirst = SharedMobileStore()
        workspaceFirst.acceptFrame("""{"kind":"workspaces","items":[]}""")
        assertTrue(workspaceFirst.canRestoreOfflineSessions())
        assertTrue(!workspaceFirst.canRestoreOfflineWorkspaces())
        val workspaceRestored = workspaceFirst.restoreOfflineCatalog(catalog())
        assertEquals("s", workspaceRestored.sessions.single().id)
        assertTrue(workspaceRestored.workspaces.isEmpty())
        assertTrue(!workspaceFirst.canRestoreOfflineCatalog())

        val sessionFirst = SharedMobileStore()
        sessionFirst.acceptFrame("""{"kind":"sessions","items":[]}""")
        assertTrue(!sessionFirst.canRestoreOfflineSessions())
        assertTrue(sessionFirst.canRestoreOfflineWorkspaces())
        val sessionRestored = sessionFirst.restoreOfflineCatalog(catalog())
        assertTrue(sessionRestored.sessions.isEmpty())
        assertEquals("w", sessionRestored.workspaces.single().workspaceId)
        assertNull(sessionRestored.selectedSessionId)

        val selectionFirst = SharedMobileStore()
        selectionFirst.selectSession("user-choice")
        assertTrue(!selectionFirst.canRestoreOfflineSelection())
        val selected = selectionFirst.restoreOfflineCatalog(catalog())
        assertEquals("user-choice", selected.selectedSessionId)
        assertEquals("s", selected.sessions.single().id)
        assertEquals("w", selected.workspaces.single().workspaceId)
    }

    @Test
    fun malformedFramesBeforeColdRestoreDoNotClaimAuthority() {
        val store = SharedMobileStore()
        for (frame in listOf(
            """{"kind":"sessions"}""",
            """{"kind":"sessions","items":null}""",
            """{"kind":"sessions","items":[{}]}""",
            """{"kind":"workspaces"}""",
            """{"kind":"workspaces","items":[{}]}"""
        )) {
            store.acceptFrame(frame)
            assertTrue(store.canRestoreOfflineSessions())
            assertTrue(store.canRestoreOfflineWorkspaces())
        }
        assertEquals("s", store.restoreOfflineCatalog(catalog()).sessions.single().id)
    }

    @Test
    fun freshArchiveListSurvivesLateSessionCatalogRestore() {
        val store = SharedMobileStore()
        store.acceptFrame("""{"kind":"workspaces","items":[],"archivedSessionIds":["s"]}""")
        val restored = store.restoreOfflineCatalog(catalog())
        assertTrue(restored.sessions.isEmpty())
        assertTrue(codec.sessionListJson(store.exportOfflineCatalog(null))!!.contains("\"archivedSessionIds\":[\"s\"]"))
        assertTrue(!store.canRestoreOfflineCatalog())
    }

    @Test
    fun invalidCatalogIsAtomicAndFutureVersionsAreRejected() {
        val store = SharedMobileStore()
        store.restoreOfflineCatalog(catalog())
        assertEquals("s", store.restoreOfflineCatalog("{}").sessions.single().id)
        assertNull(codec.normalize(catalog().replace("\"version\":1", "\"version\":99")))
        assertNull(codec.create(list.replace("\"id\":\"s\"", "\"id\":\"\""), workspaces, "w"))
        assertNull(codec.create(list.replace("缓存会话", "x".repeat(8193)), workspaces, "w"))
        assertNull(codec.normalize("x".repeat(1024 * 1024 + 1)))
        val unknown = catalog().dropLast(1) + ",\"events\":[{\"text\":\"must not persist\"}]}"
        assertTrue(!assertNotNull(codec.normalize(unknown)).contains("must not persist"))
    }

    @Test
    fun catalogRejectsDuplicateIdentifiersAndBoundedListOverflow() {
        val session = """{"id":"s","title":"目录","lastActivityEpochSeconds":1,"isRunning":false,"hasUnread":false}"""
        assertNull(codec.create("""{"sessions":[$session,$session]}""", "[]", null))
        val many = (1..4097).joinToString(",") { session.replace("\"s\"", "\"s$it\"") }
        assertNull(codec.create("""{"sessions":[$many]}""", "[]", null))
        val manyWorkspaces = (1..513).joinToString(",") { workspaces.drop(1).dropLast(1).replace("\"w\"", "\"w$it\"") }
        assertNull(codec.create("{}", "[$manyWorkspaces]", null))
        val oversized = list.replace("缓存会话", "x".repeat(1024 * 1024))
        assertNull(codec.create(oversized, "[]", null))
    }

    @Test
    fun emptyCatalogIsValidAndHasOnlyNavigationKeys() {
        val empty = assertNotNull(codec.create("{}", "[]", null))
        val json = wireJson.parseToJsonElement(empty).jsonObject
        assertTrue(json.keys.all { it in setOf("version", "sessionList", "workspaces", "selectedWorkspaceId", "conversationTargets", "teamAvatars") })
        assertTrue(SharedMobileStore().restoreOfflineCatalog(empty).sessions.isEmpty())
    }
}
