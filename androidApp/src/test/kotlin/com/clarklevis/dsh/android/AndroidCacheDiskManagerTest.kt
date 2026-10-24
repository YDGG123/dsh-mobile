package com.clarklevis.dsh.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCacheDiskManagerTest {
    @Test fun categoryCleanupKeepsOtherResourcesAndActiveFiles() {
        val categories = listOf(
            AndroidCacheDiskManager.PREVIEWS, AndroidCacheDiskManager.IMAGES,
            AndroidCacheDiskManager.TEMPORARY, AndroidCacheDiskManager.UPDATES
        )
        categories.forEach { selected ->
            directory { root ->
                val files = categories.associateWith { category -> file(root, "$category/group/content", 20) }
                val userFile = file(root, "exports/saved.txt", 12)
                val manager = manager(root)
                val epochs = categories.associateWith(manager::categoryEpoch)
                val lease = manager.lease(files.getValue(selected))
                manager.clearCategory(selected)
                categories.forEach { category ->
                    assertEquals(epochs.getValue(category) + if (category == selected) 1 else 0, manager.categoryEpoch(category))
                }
                files.values.forEach { assertTrue(it.exists()) }
                lease.close()
                val stats = manager.clearCategory(selected)
                assertFalse(files.getValue(selected).exists())
                files.filterKeys { it != selected }.values.forEach { assertTrue(it.exists()) }
                assertTrue(userFile.exists())
                assertEquals(60L, stats.totalBytes)
            }
        }
    }

    @Test fun statisticsDoesNotCleanTemporaryFilesOrExpiredImages() = directory { root ->
        val temporary = file(root, "workspace-files/orphan/file", 20)
        val image = file(root, "gateway-image-attachments/g/image", 15, 0)
        val manager = manager(root, now = { Long.MAX_VALUE / 2 })
        val stats = manager.statistics()
        assertEquals(35L, stats.totalBytes)
        assertTrue(temporary.exists())
        assertTrue(image.exists())
    }

    @Test fun orphanCleanupRetainsActiveDownloadAndPreservesUnmanagedSources() = directory { root ->
        val active = file(root, "workspace-files/active/video.mp4", 20)
        val orphan = file(root, "workspace-files/dead/video.mp4", 15)
        val pending = file(root, "workspace-previews/.pending-crashed/video.mp4", 10)
        val empty = File(root, "workspace-files/empty").apply { mkdirs() }
        val upload = file(root, "uploads/original.jpg", 25)
        val manager = manager(root)
        val first = manager.lease(active)
        val second = manager.lease(active)
        manager.maintain()
        assertTrue(active.exists())
        assertFalse(orphan.exists())
        assertFalse(pending.exists())
        assertFalse(empty.exists())
        assertTrue(upload.exists())
        first.close()
        manager.maintain(clear = true)
        assertTrue(active.exists())
        second.close()
        manager.maintain()
        assertFalse(active.exists())
        assertTrue(upload.exists())
    }

    @Test fun lruStopsAtTargetAndCountsAllGatewayImages() = directory { root ->
        val old = file(root, "gateway-image-attachments/g1/a", 20, 100)
        val next = file(root, "gateway-image-attachments/g2/b", 20, 200)
        val recent = file(root, "gateway-image-attachments/g2/c", 20, 300)
        val manager = manager(root, imageMaximum = 50, imageTarget = 40)
        val stats = manager.maintain()
        assertFalse(old.exists())
        assertTrue(next.exists())
        assertTrue(recent.exists())
        assertEquals(40L, stats.imageBytes)
    }

    @Test fun protectedOversizeIsReportedAndReclaimedAfterLastLease() = directory { root ->
        val file = file(root, "workspace-previews/large/video", 100)
        val manager = manager(root, previewMaximum = 50, previewTarget = 40)
        val lease = manager.lease(file)
        val stats = manager.maintain(clear = true)
        assertTrue(file.exists())
        assertEquals(100L, stats.protectedBytes)
        assertTrue(stats.pressureUnresolved)
        lease.close()
        assertEquals(0L, manager.maintain().previewBytes)
    }

    @Test fun globalBudgetIncludesDatabaseAndReclaimsToLowWatermark() = directory { root ->
        file(root, "workspace-previews/old/file", 40, 100).parentFile!!.setLastModified(100)
        val recent = file(root, "gateway-image-attachments/g1/new", 20, 200)
        val manager = AndroidCacheDiskManager(
            root,
            databaseBytes = { 60 },
            availableBytes = { 10_000 },
            now = { 1000 },
            limits = AndroidCacheDiskManager.Limits(100, 80, 100, 80, 100, 80, 32, 0)
        )
        assertEquals(80L, manager.maintain().totalBytes)
        assertTrue(recent.exists())
    }

    @Test fun lowFreeSpaceReclaimsButReportsWhenProtectedBytesPreventRecovery() = directory { root ->
        val file = file(root, "workspace-previews/active/file", 30)
        val manager = AndroidCacheDiskManager(
            root,
            availableBytes = { 0 },
            now = { 1000 },
            limits = AndroidCacheDiskManager.Limits(100, 80, 100, 80, 100, 80, 32, 50)
        )
        val lease = manager.lease(file)
        assertTrue(manager.maintain().pressureUnresolved)
        assertFalse(manager.cacheablePreview(10))
        lease.close()
        manager.maintain()
        assertFalse(file.exists())
    }

    @Test fun handoffLeaseSurvivesRestartAndExpires() = directory { root ->
        var time = 1000L
        val file = file(root, "workspace-files/active/movie.mp4", 30)
        val first = manager(root, now = { time })
        first.retainExternal(file)
        val restarted = manager(root, now = { time })
        assertTrue(restarted.maintain(clear = true).protectedBytes >= 30)
        assertTrue(file.exists())
        time += 6L * 60 * 60 * 1000 + 1
        restarted.maintain()
        assertFalse(file.exists())
    }

    @Test fun symlinkCleanupDoesNotVisitExternalFiles() = directory { root ->
        val outside = Files.createTempDirectory("cache-outside").toFile()
        try {
            val original = file(outside, "precious", 17)
            val preview = File(root, "workspace-previews/key").apply { mkdirs() }
            Files.createSymbolicLink(File(preview, "outside").toPath(), outside.toPath())
            val stats = manager(root).maintain(clear = true)
            assertEquals(0L, stats.previewBytes)
            assertTrue(original.exists())
            assertEquals(17L, original.length())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test fun clearingAdvancesEpochAndNeverDeletesUnmanagedFile() = directory { root ->
        val outside = file(root, "exports/saved.mp4", 10)
        val manager = manager(root)
        manager.deleteTemporary(outside)
        assertTrue(outside.exists())
        manager.maintain(clear = true)
        assertEquals(1L, manager.epoch)
        assertTrue(outside.exists())
    }

    @Test fun categoryRootSymlinkNeverDeletesExternalSource() = directory { root ->
        val outside = Files.createTempDirectory("outside-source").toFile()
        try {
            val original = file(outside, "original", 100)
            Files.createSymbolicLink(File(root, "workspace-files").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "workspace-previews").toPath(), outside.toPath())
            manager(root).maintain(clear = true)
            assertTrue(original.exists())
            assertEquals(100L, original.length())
        } finally {
            File(root, "workspace-files").delete()
            File(root, "workspace-previews").delete()
            outside.deleteRecursively()
        }
    }

    @Test fun updateDownloadsAndReadyInstallersAreClassifiedAndProtected() = directory { root ->
        val apk = file(root, "updates/update.apk", 30)
        val manager = manager(root)
        val lease = manager.lease(apk.parentFile!!)
        assertEquals(30L, manager.statistics().updateBytes)
        manager.maintain(clear = true)
        assertTrue(apk.exists())
        lease.close()
        manager.maintain()
        assertFalse(apk.exists())
        val partial = file(root, "updates/update.part", 20)
        manager.maintain()
        assertFalse(partial.exists())
    }

    @Test fun slowMetadataScanDoesNotBlockMainThreadLease() = directory { root ->
        file(root, "workspace-previews/test/file", 10)
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val manager = AndroidCacheDiskManager(root, allocatedBytes = { entry ->
            if (entry.isFile) {
                entered.countDown()
                check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
                entry.length()
            } else {
                0
            }
        })
        try {
            val scanning = executor.submit<AndroidCacheDiskManager.Statistics> { manager.statistics() }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val start = System.nanoTime()
            manager.lease(File(root, "workspace-files/other/file")).close()
            assertTrue(System.nanoTime() - start < 200_000_000L)
            resume.countDown()
            scanning.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            resume.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun managedDirectoryRejectsSymlinkAncestorsAndOutsidePaths() = directory { root ->
        val outside = Files.createTempDirectory("outside-managed-root").toFile()
        try {
            val manager = manager(root)
            Files.createSymbolicLink(File(root, "gateway-image-attachments").toPath(), outside.toPath())
            assertFalse(manager.safeManagedDirectory(File(root, "gateway-image-attachments/gateway")))
            assertFalse(manager.safeManagedDirectory(outside))
            assertTrue(manager.safeManagedDirectory(File(root, "workspace-files/uuid")))
        } finally {
            File(root, "gateway-image-attachments").delete()
            outside.deleteRecursively()
        }
    }

    @Test fun interruptedUpdateWithdrawalRemainsCountedAndReclaimedAfterRestart() = directory { root ->
        val residue = file(root, ".dsh-cache-trash-interrupted/update.apk", 30)
        val restarted = manager(root)
        assertEquals(30L, restarted.statistics().updateBytes)
        restarted.maintain()
        assertFalse(residue.exists())
    }

    private fun manager(
        root: File,
        imageMaximum: Long = 100,
        imageTarget: Long = 80,
        previewMaximum: Long = 100,
        previewTarget: Long = 80,
        now: () -> Long = { 1000L }
    ) = AndroidCacheDiskManager(
        root,
        availableBytes = { 10_000 },
        now = now,
        limits = AndroidCacheDiskManager.Limits(
            300,
            240,
            previewMaximum,
            previewTarget,
            imageMaximum,
            imageTarget,
            32,
            0
        )
    )

    private fun file(root: File, path: String, size: Int, modified: Long = 500): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(ByteArray(size))
        setLastModified(modified)
    }

    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("disk-manager-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
