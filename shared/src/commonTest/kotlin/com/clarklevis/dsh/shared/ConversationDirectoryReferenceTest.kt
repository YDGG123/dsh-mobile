package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationDirectoryReferenceTest {
    @Test
    fun explicitDirectoryOpensBrowserWithoutDownloadOrCacheLookup() {
        val store = SharedWorkspaceFileStore()
        store.setWorkspaceSession("child", "lead")
        val result = store.resolveReference("child", "promo/analysis/stills/", "r1", true, null)
        assertEquals("child", result.directory?.sessionId)
        assertEquals("promo/analysis/stills", result.directory?.path)
        assertNull(result.request)
        assertNull(result.cacheLookup)
        val list = store.load("child", result.directory!!.path, "l1")
        assertTrue(list.request!!.payload.contains("\"sessionId\":\"lead\""))
        assertTrue(list.request.payload.contains("\"path\":\"promo/analysis/stills\""))
    }

    @Test
    fun absoluteDirectoryIsRestrictedToOwningWorkspace() {
        val store = SharedWorkspaceFileStore()
        val result = store.resolveReference("s1", "/work/空 格/stills/", "r1", true, "/work")
        assertEquals("空 格/stills", result.directory?.path)
        assertEquals(".", store.resolveReference("s1", "/work/", "r2", true, "/work").directory?.path)
        for (path in listOf("/work-other/stills/", "/work/../secret/", "../secret/", "/etc/")) {
            val invalid = store.resolveReference("s1", path, "invalid", true, "/work")
            assertNull(invalid.directory)
            assertEquals("file-directory-path-invalid", invalid.snapshot.lastError)
        }
    }

    @Test
    fun extensionlessDirectoryIsRecognizedByCorrelatedGatewayResponse() {
        val store = SharedWorkspaceFileStore()
        store.setWorkspaceSession("child", "lead")
        store.resolveReference("child", "/work/stills", "r1", true, "/work")
        assertNull(store.acceptFrame(error("old", "lead")).directory)
        assertNull(store.acceptFrame(error("r1", "other-team")).directory)
        val result = store.acceptFrame(error("r1", "lead"))
        assertEquals("stills", result.directory?.path)
        assertEquals("child", result.directory?.sessionId)
        assertNull(result.snapshot.lastError)
    }

    @Test
    fun ordinaryFilePreviewNeverTurnsIntoDirectoryAndLateResponseIsIgnored() {
        val store = SharedWorkspaceFileStore()
        store.resolve("s1", "a.txt", "r1", true)
        assertNull(store.acceptFrame(error("r1", "s1")).directory)
        store.resolveReference("s1", "stills", "r2", true, null)
        store.cancel()
        assertNull(store.acceptFrame(error("r2", "s1")).directory)
        store.reset("other")
        assertNull(store.acceptFrame(error("r2", "s1")).directory)
    }

    @Test
    fun legacyGatewaySupportsExplicitDirectoriesAndCorrelatedOpenFallback() {
        val store = SharedWorkspaceFileStore()
        assertEquals("stills", store.resolveReference("s1", "./stills/", "r1", false, null).directory?.path)
        store.resolveReference("s1", "stills", "r2", false, null)
        val result = store.acceptFrame(error("r2", "s1", "file-download-open"))
        assertEquals("stills", result.directory?.path)
    }

    private fun error(
        id: String,
        session: String,
        type: String = "file-resolve",
    ) = """
        {"kind":"error","requestType":"$type","requestId":"$id","sessionId":"$session",
        "code":"file-not-regular","message":"only regular files can be previewed"}
        """
}
