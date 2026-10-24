package com.clarklevis.dsh.android.platform

import android.content.Context
import com.clarklevis.dsh.android.AndroidCacheDiskManager
import com.clarklevis.dsh.android.AndroidCacheStorage
import com.clarklevis.dsh.shared.platform.GatewayAttachmentCache
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidAttachmentCache(
    context: Context,
    private val ttlMilliseconds: Long = DEFAULT_TTL_MILLISECONDS,
    private val now: () -> Long = System::currentTimeMillis,
    gatewayId: String? = null,
    private val writeFile: (File, ByteArray) -> Unit = { file, bytes -> file.writeBytes(bytes) }
) : GatewayAttachmentCache {
    private val directory = context.cacheDir.resolve(gatewayId?.let { "gateway-image-attachments/$it" } ?: "gateway-image-attachments")
    private val storage = AndroidCacheStorage.shared(context)
    private val memoryPrefix = directory.absolutePath + "/"
    private val readEpochs = LinkedHashMap<String, Long>()
    private val instanceEpoch = storage.categoryEpoch(AndroidCacheDiskManager.IMAGES)

    override suspend fun read(attachmentId: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!storage.safeManagedDirectory(directory)) return@withContext null
        val epoch = storage.categoryEpoch(AndroidCacheDiskManager.IMAGES)
        synchronized(lock) {
            readEpochs[attachmentId] = epoch
            while (readEpochs.size > 512) readEpochs.remove(readEpochs.keys.first())
            val memoryKey = memoryPrefix + attachmentId
            val entry = memory[memoryKey]
            if (entry != null && entry.writtenAt + ttlMilliseconds > now() && entry.epoch == epoch) {
                return@withContext entry.bytes.copyOf()
            }
            memory.remove(memoryKey)?.let { memoryBytes -= it.bytes.size }
        }
        val file = fileFor(attachmentId)
        val lease = storage.lease(file)
        try {
            if (!file.isFile || file.lastModified() + ttlMilliseconds <= now() || file.length() > DISK_LIMIT_BYTES) {
                return@withContext null
            }
            val bytes = runCatching {
                file.inputStream().use { readBytesWithLimit(it, DISK_LIMIT_BYTES.toInt()) }
            }.getOrNull() ?: return@withContext null
            if (epoch != storage.categoryEpoch(AndroidCacheDiskManager.IMAGES)) return@withContext null
            file.setLastModified(now())
            putMemory(attachmentId, bytes, epoch)
            bytes
        } finally {
            lease.close()
        }
    }

    override suspend fun write(attachmentId: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!storage.safeManagedDirectory(directory)) return@withContext false
        val epoch = synchronized(lock) { readEpochs.remove(attachmentId) ?: instanceEpoch }
        if (bytes.size > DISK_LIMIT_BYTES ||
            epoch != storage.categoryEpoch(AndroidCacheDiskManager.IMAGES) ||
            !storage.canWriteTemporary(bytes.size.toLong())
        ) {
            return@withContext false
        }
        val target = fileFor(attachmentId)
        val temporary = File(directory, ".pending-${UUID.randomUUID()}.tmp")
        val lease = storage.lease(temporary)
        try {
            directory.mkdirs()
            writeFile(temporary, bytes)
            val committed = storage.access {
                if (epoch != storage.categoryEpoch(AndroidCacheDiskManager.IMAGES) || !storage.canRemove(target)) return@access false
                if (target.exists() && !target.delete()) return@access false
                if (!temporary.renameTo(target)) return@access false
                target.setLastModified(now())
                true
            }
            if (committed) {
                putMemory(attachmentId, bytes, epoch)
                storage.maintain()
            }
            committed && target.isFile
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            lease.close()
            temporary.delete()
        }
    }

    override suspend fun removeExpired() = withContext(Dispatchers.IO) {
        if (!storage.safeManagedDirectory(directory)) return@withContext
        directory.listFiles().orEmpty().forEach { file ->
            if (file.lastModified() + ttlMilliseconds <= now()) {
                storage.access {
                    if (storage.canRemove(file)) file.delete()
                }
            }
        }
        storage.maintain()
        Unit
    }

    private fun putMemory(attachmentId: String, bytes: ByteArray, epoch: Long) {
        val memoryKey = memoryPrefix + attachmentId
        val owned = bytes.copyOf()
        synchronized(lock) {
            memory.remove(memoryKey)?.let { memoryBytes -= it.bytes.size }
            memory[memoryKey] = MemoryEntry(owned, now(), epoch)
            memoryBytes += bytes.size
            val iterator = memory.entries.iterator()
            while (memoryBytes > MEMORY_LIMIT_BYTES && iterator.hasNext()) {
                memoryBytes -= iterator.next().value.bytes.size
                iterator.remove()
            }
        }
    }

    private fun fileFor(attachmentId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(attachmentId.toByteArray())
        return directory.resolve(digest.joinToString("") { "%02x".format(it) })
    }

    private data class MemoryEntry(val bytes: ByteArray, val writtenAt: Long, val epoch: Long)

    companion object {
        private val memory = LinkedHashMap<String, MemoryEntry>(16, 0.75f, true)
        private var memoryBytes = 0
        private val lock = Any()
        fun trimMemory() = synchronized(lock) {
            memory.clear()
            memoryBytes = 0
        }

        private const val DEFAULT_TTL_MILLISECONDS = 7L * 24 * 60 * 60 * 1_000
        private const val MEMORY_LIMIT_BYTES = 32 * 1_024 * 1_024
        private const val DISK_LIMIT_BYTES = 32L * 1_024 * 1_024
    }
}

internal data class CacheFileMetadata(
    val name: String,
    val byteCount: Long,
    val lastModified: Long,
    val isFile: Boolean = true
)

internal fun attachmentCacheEvictions(
    files: List<CacheFileMetadata>,
    now: Long,
    ttlMilliseconds: Long,
    maximumBytes: Long
): Set<String> {
    val cutoff = now - ttlMilliseconds
    val evicted = files.filter {
        !it.isFile || it.name.endsWith(".tmp") || it.lastModified <= cutoff
    }.mapTo(mutableSetOf(), CacheFileMetadata::name)
    val valid = files.filter { it.name !in evicted }.sortedBy(CacheFileMetadata::lastModified)
    var total = valid.sumOf(CacheFileMetadata::byteCount)
    valid.forEach { file ->
        if (total > maximumBytes) {
            evicted += file.name
            total -= file.byteCount
        }
    }
    return evicted
}
