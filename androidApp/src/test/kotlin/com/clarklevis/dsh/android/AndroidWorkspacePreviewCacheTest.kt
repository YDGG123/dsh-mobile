package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileCacheLookup
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileCompletion
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AndroidWorkspacePreviewCacheTest {
    private val hash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    private val lookup = SharedWorkspaceFileCacheLookup("r1", "s1", "build/a.txt", "a.txt", "text/plain", 5, 10.5)
    private val completion = SharedWorkspaceFileCompletion("s1", "build/a.txt", "a.txt", "text/plain", 5, hash, "preview", 10.5)

    @Test
    fun persistsAcrossInstancesAndSeparatesRemoteVersionsGatewaysAndSessions() = withDirectory { directory ->
        val root = directory.resolve("cache")
        val source = directory.resolve("source").apply { writeText("hello") }
        val file = AndroidWorkspacePreviewCache(root, "g1").store(source, completion)
        assertNotNull(file)
        assertEquals("hello", AndroidWorkspacePreviewCache(root, "g1").find(lookup)?.readText())
        assertNull(AndroidWorkspacePreviewCache(root, "g2").find(lookup))
        val cache = AndroidWorkspacePreviewCache(root, "g1")
        assertNull(cache.find(lookup.copy(sessionId = "s2")))
        assertNull(cache.find(lookup.copy(path = "other/a.txt")))
        assertNull(cache.find(lookup.copy(modifiedAt = 11.0)))
        assertNull(cache.find(lookup.copy(size = 6)))
    }

    @Test
    fun corruptOrDeletedCacheFallsBackToDownload() = withDirectory { directory ->
        val cache = AndroidWorkspacePreviewCache(directory.resolve("cache"), "g1")
        val source = directory.resolve("source").apply { writeText("hello") }
        val file = requireNotNull(cache.store(source, completion))
        file.writeText("other") // 长度相同也必须检测到损坏。
        assertNull(cache.find(lookup))
        requireNotNull(cache.store(source, completion)).delete()
        assertNull(cache.find(lookup))
    }

    @Test
    fun cacheFailurePreservesSourceAndUnversionedFilesAreNotCached() = withDirectory { directory ->
        val source = directory.resolve("source").apply { writeText("hello") }
        val root = directory.resolve("cache").apply { writeText("cannot create a directory here") }
        val cache = AndroidWorkspacePreviewCache(root, "g1")
        assertNull(cache.store(source, completion))
        assertEquals("hello", source.readText())
        assertNull(cache.store(source, completion.copy(modifiedAt = null)))
        assertNull(cache.store(source, completion.copy(purpose = "download")))
    }

    @Test
    fun clearDuringDownloadRejectsOldCacheCommitAndLeavesSourceForPreview() = withDirectory { directory ->
        val storage = AndroidCacheDiskManager(directory, availableBytes = { Long.MAX_VALUE })
        val source = directory.resolve("source").apply { writeText("hello") }
        val cache = AndroidWorkspacePreviewCache(directory.resolve("workspace-previews"), "g1", storage)
        val epoch = storage.epoch
        storage.maintain(clear = true)
        assertNull(cache.store(source, completion, epoch))
        assertTrue(source.exists())
        assertNull(cache.find(lookup))
    }

    @Test
    fun oversizedAndLowSpaceDownloadsStayTemporary() = withDirectory { directory ->
        val source = directory.resolve("source").apply { writeText("hello") }
        val storage = AndroidCacheDiskManager(directory, availableBytes = { 0 })
        val cache = AndroidWorkspacePreviewCache(directory.resolve("workspace-previews"), "g1", storage)
        assertNull(cache.store(source, completion))
        assertNull(cache.store(source, completion.copy(size = 145L * 1024 * 1024)))
        assertEquals("hello", source.readText())
    }

    @Test
    fun fallbackTemporaryIsProtectedUntilPreviewAndExportBothRelease() = withDirectory { directory ->
        val storage = AndroidCacheDiskManager(directory, availableBytes = { Long.MAX_VALUE })
        val source = directory.resolve("workspace-files/request/movie").apply {
            parentFile!!.mkdirs()
            writeText("hello")
        }
        val preview = storage.lease(source)
        val export = storage.lease(source)
        storage.maintain(clear = true)
        assertTrue(source.exists())
        preview.close()
        storage.deleteTemporary(source)
        assertTrue(source.exists())
        export.close()
        storage.deleteTemporary(source)
        assertFalse(source.exists())
        assertFalse(source.parentFile!!.exists())
    }

    @Test
    fun reservedRemoteNamesCannotOverwriteHandoffMetadata() = withDirectory { directory ->
        val storage = AndroidCacheDiskManager(directory, availableBytes = { Long.MAX_VALUE })
        val source = directory.resolve("source").apply { writeText("hello") }
        val cache = AndroidWorkspacePreviewCache(directory.resolve("workspace-previews"), "g1", storage)
        val saved = requireNotNull(cache.store(source, completion.copy(name = ".lease-until")))
        storage.retainExternal(saved)
        assertEquals("hello", saved.readText())
        assertEquals("file", saved.name)
    }

    private fun withDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("workspace-preview-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
