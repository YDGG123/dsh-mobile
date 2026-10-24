package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedTeamStore
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChildWorkspaceFilesTest {
    @Test
    fun nestedMembersResolveToTheirOwnTeamWorkspace() {
        val teams = teams()
        assertEquals("lead", teams.workspaceSessionId("child"))
        assertEquals("lead", teams.workspaceSessionId("grandchild"))
        assertEquals("other-lead", teams.workspaceSessionId("other-child"))
        assertEquals("lead", teams.workspaceSessionId("lead"))
        assertEquals("unknown", teams.workspaceSessionId("unknown"))
    }

    @Test
    fun childDownloadUsesRootForEveryRequestAndKeepsChildCacheAndCompletion() {
        val teams = teams()
        val store = SharedWorkspaceFileStore()
        store.setWorkspaceSession("grandchild", teams.workspaceSessionId("grandchild"))
        val start = store.resolve("grandchild", "/work/a.txt", "r1", true, true)
        assertTrue(start.request!!.payload.contains("\"sessionId\":\"lead\""))
        assertNotNull(teams.decorateRequestJson(start.request.payload))
        assertNull(store.acceptFrame(resolved("r1", "other-lead")).cacheLookup)
        assertNull(store.acceptFrame(resolved("old", "lead")).cacheLookup)
        val lookup = store.acceptFrame(resolved("r1", "lead")).cacheLookup!!
        assertEquals("grandchild", lookup.sessionId)
        // 切换绑定也不能把已经开始的下载转发到另一个团队。
        store.setWorkspaceSession("other-child", "other-lead")
        val download = store.completeCacheLookup("r1", false)
        assertTrue(download.request!!.payload.contains("\"sessionId\":\"lead\""))
        val opened =
            store.acceptFrame(
                """
            {
              "kind": "file-download-opened",
              "requestId": "r1-download",
              "transferId": "t1",
              "sessionId": "lead",
              "path": "a.txt",
              "name": "a.txt",
              "size": 5,
              "chunkBytes": 100
            }
            """,
            )
        assertEquals("grandchild", opened.snapshot.activeDownload?.sessionId)
        val eof =
            store.acceptFrame(
                """
            {
              "kind": "file-download-chunk",
              "transferId": "t1",
              "offset": 0,
              "data": "aGVsbG8=",
              "eof": true,
              "sha256": "${WorkspacePreviewCacheFlowTest.HASH}"
            }
            """,
            )
        assertTrue(eof.request!!.payload.contains("\"sessionId\":\"lead\""))
        assertNull(store.acceptFrame(resolved("t1-verify", "other-lead")).completion)
        val completed = store.acceptFrame(resolved("t1-verify", "lead")).completion!!
        assertEquals("grandchild", completed.sessionId)
        assertEquals(10.5, completed.modifiedAt)
        assertEquals(WorkspacePreviewCacheFlowTest.HASH, completed.sha256)
    }

    @Test
    fun childCacheHitStaysInChildAndResetRejectsLateLookup() {
        val store = SharedWorkspaceFileStore()
        store.setWorkspaceSession("child", "lead")
        store.resolve("child", "a.txt", "r1", true, true)
        store.acceptFrame(resolved("r1", "lead"))
        assertEquals("child", store.completeCacheLookup("r1", true).cacheHit?.sessionId)
        store.resolve("child", "a.txt", "r2", true, true)
        store.acceptFrame(resolved("r2", "lead"))
        store.reset("other-child")
        assertNull(store.completeCacheLookup("r2", true).cacheHit)
        assertNull(store.acceptFrame(resolved("r2", "lead")).completion)
        val unbound = store.download("lead", "a.txt", "d1", "download")
        assertTrue(unbound.request!!.payload.contains("\"sessionId\":\"lead\""))
    }

    @Test
    fun directoryAndLegacyDownloadUseRootWithoutChangingViewSession() {
        val store = SharedWorkspaceFileStore()
        store.setWorkspaceSession("child", "lead")
        val list = store.load("child", "stills", "l1")
        assertTrue(list.request!!.payload.contains("\"sessionId\":\"lead\""))
        val loaded =
            store.acceptFrame(
                """
            {
              "kind": "file-list",
              "requestId": "l1",
              "sessionId": "lead",
              "path": "stills",
              "entries": [
                {
                  "name": "a.txt",
                  "path": "stills/a.txt",
                  "kind": "file",
                  "bytes": 5
                }
              ]
            }
            """,
            )
        assertEquals("child", loaded.snapshot.sessionId)
        assertEquals(
            "stills/a.txt",
            loaded.snapshot.entries
                .single()
                .path,
        )
        val legacy = store.resolve("child", "stills/a.txt", "legacy", false)
        assertTrue(legacy.request!!.payload.contains("\"sessionId\":\"lead\""))
        assertEquals("file-download-open", legacy.request.requestType)
    }

    private fun teams() =
        SharedTeamStore().apply {
            assertTrue(
                restoreRoutingJson(
                    """
            [
              {
                "sessionId": "child",
                "parentSessionId": "lead",
                "subagentMode": "continuable"
              },
              {
                "sessionId": "grandchild",
                "parentSessionId": "child",
                "subagentMode": "continuable"
              },
              {
                "sessionId": "other-child",
                "parentSessionId": "other-lead",
                "subagentMode": "continuable"
              }
            ]
            """,
                ),
            )
        }

    private fun resolved(
        request: String,
        session: String,
    ) =
        """
        {"kind":"file-resolved","requestId":"$request","sessionId":"$session",
        "path":"a.txt","name":"a.txt","size":5,"modifiedAt":10.5}
        """
}
