package com.clarklevis.dsh.android

import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileCacheLookup
import com.clarklevis.dsh.shared.facade.SharedWorkspaceFileCompletion
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 版本目录不可变；大文件校验与复制不占用管理器锁。 */
internal class AndroidWorkspacePreviewCache(
    private val root: File,
    private val gatewayId: String,
    private val storage: AndroidCacheDiskManager? = null
) {
    private fun <T> access(block: () -> T): T = if (storage != null) storage.access(block) else synchronized(LOCK, block)

    fun find(version: SharedWorkspaceFileCacheLookup, onReady: (File) -> Unit = {}): File? {
        if (!safeRoot()) return null
        val directory = directory(version.sessionId, version.path, version.size, version.modifiedAt)
        if (directory.canonicalFile != File(root.canonicalFile, directory.name)) return null
        val file = File(directory, safeName(version.name))
        if (file.canonicalFile != File(directory.canonicalFile, file.name)) return null
        val epoch = storage?.categoryEpoch(AndroidCacheDiskManager.PREVIEWS) ?: 0
        val lease = access { storage?.lease(file) }
        var valid = false
        return try {
            val metadata = File(directory, "sha256")
            check(metadata.length() <= 128 && metadata.canonicalFile == File(directory.canonicalFile, "sha256"))
            val expected = metadata.readText()
            valid = file.isFile && file.length() == version.size && digest(file) == expected
            if (!valid) {
                null
            } else {
                access {
                    if (file.isFile && (storage?.categoryEpoch(AndroidCacheDiskManager.PREVIEWS) ?: 0) == epoch) {
                        directory.setLastModified(System.currentTimeMillis())
                        onReady(file)
                        file
                    } else {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            lease?.close()
            if (!valid) {
                access {
                    if (storage?.canRemove(directory) != false) directory.deleteRecursively()
                }
            }
        }
    }

    fun store(
        source: File,
        completion: SharedWorkspaceFileCompletion,
        expectedEpoch: Long = storage?.categoryEpoch(AndroidCacheDiskManager.PREVIEWS) ?: 0,
        onReady: (File) -> Unit = {}
    ): File? {
        if (!safeRoot()) return null
        val modifiedAt = completion.modifiedAt ?: return null
        if (completion.purpose != "preview" ||
            completion.size > MAX_BYTES ||
            storage?.cacheablePreview(completion.size) == false
        ) {
            return null
        }
        val directory = directory(completion.sessionId, completion.path, completion.size, modifiedAt)
        val staging = File(root, ".pending-${UUID.randomUUID()}")
        val lease = access {
            storage?.lease(staging)
        }
        return try {
            check(staging.mkdirs())
            val file = File(staging, safeName(completion.name))
            source.copyTo(file)
            File(staging, "sha256").writeText(completion.sha256)
            File(staging, "gateway").writeText(gatewayId)
            val saved = access {
                if ((storage?.categoryEpoch(AndroidCacheDiskManager.PREVIEWS) ?: 0) != expectedEpoch) return@access null
                // 已存在的不可变版本可能正被别的 reader 使用，不覆盖它。
                if (directory.exists()) return@access null
                check(staging.renameTo(directory))
                File(directory, file.name).also(onReady)
            }
            lease?.close()
            if (saved != null) {
                if (storage != null) storage.maintain() else prune(directory)
            }
            saved
        } catch (_: Exception) {
            null // 完成校验的 source 由预览租约持有，关闭时回收。
        } finally {
            lease?.close()
            staging.deleteRecursively()
        }
    }

    private fun directory(sessionId: String, path: String, size: Long, modifiedAt: Double): File {
        val identity = listOf(gatewayId, sessionId, path, size.toString(), modifiedAt.toString())
            .joinToString("") { "${it.length}:$it" }
        val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, key)
    }

    private fun safeRoot(): Boolean = runCatching {
        root.canonicalFile == File(root.parentFile?.canonicalFile, root.name)
    }.getOrDefault(false)

    private fun safeName(name: String): String = AndroidCacheDiskManager.safeFileName(name)

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun prune(current: File) {
        val entries = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy(File::lastModified).orEmpty()
        var bytes = entries.sumOf { it.listFiles().orEmpty().sumOf(File::length) }
        entries.forEach { directory ->
            if (directory != current && bytes > MAX_BYTES) {
                val reclaimed = directory.listFiles().orEmpty().sumOf(File::length)
                if (directory.deleteRecursively()) bytes -= reclaimed
            }
        }
    }

    private companion object {
        val LOCK = Any()
        const val MAX_BYTES = 144L * 1024 * 1024
    }
}
