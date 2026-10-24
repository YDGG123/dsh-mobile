package com.clarklevis.dsh.android

import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 仅扫描明确可重建的目录；用户导出、草稿、待发与上传源不在管理范围。 */
internal class AndroidCacheDiskManager(
    private val root: File,
    private val databaseBytes: () -> Long = { 0 },
    private val availableBytes: () -> Long = { root.usableSpace },
    private val now: () -> Long = System::currentTimeMillis,
    private val limits: Limits = Limits(),
    private val allocatedBytes: (File) -> Long = { if (it.isFile) it.length() else 0 },
    private val log: (String) -> Unit = {}
) {
    data class Limits(
        val maximumBytes: Long = 384L * MIB,
        val targetBytes: Long = 288L * MIB,
        val previewMaximumBytes: Long = 192L * MIB,
        val previewTargetBytes: Long = 144L * MIB,
        val imageMaximumBytes: Long = 32L * MIB,
        val imageTargetBytes: Long = 24L * MIB,
        val temporaryMaximumBytes: Long = 32L * MIB,
        val minimumFreeBytes: Long = 128L * MIB
    )

    data class Statistics(
        val previewBytes: Long,
        val imageBytes: Long,
        val temporaryBytes: Long,
        val updateBytes: Long,
        val chatBytes: Long,
        val protectedBytes: Long,
        val freeBytes: Long,
        val pressureUnresolved: Boolean
    ) {
        val totalBytes: Long get() = previewBytes + imageBytes + temporaryBytes + updateBytes + chatBytes
    }

    private data class Entry(val file: File, val category: String, val bytes: Long, val modifiedAt: Long)
    private val leases = mutableMapOf<String, Int>()
    private val maintenanceLock = ReentrantLock()

    @Volatile var epoch: Long = 0
        private set
    private val categoryEpochs = mutableMapOf<String, Long>()

    @Synchronized
    fun categoryEpoch(category: String): Long = epoch + (categoryEpochs[category] ?: 0)

    /** 外部播放器延迟打开文件；租约落盘，进程重启后的清扫也会尊重它。 */
    @Synchronized
    fun retainExternal(file: File) {
        val directory = file.parentFile ?: return
        val parent = directory.canonicalFile.parentFile
        if (parent != File(root, TEMPORARY).canonicalFile &&
            parent != File(root, PREVIEWS).canonicalFile &&
            directory.canonicalFile != File(root, UPDATES).canonicalFile
        ) {
            return
        }
        File(directory, ".lease-until").writeText((now() + EXTERNAL_LEASE_MILLIS).toString())
    }

    /** 同一进程所有 cache 实例共用此锁，避免扫描删到尚未发布的 staging。 */
    @Synchronized
    fun <T> access(block: () -> T): T = block()

    @Synchronized
    fun lease(file: File): Closeable {
        val path = file.absolutePath
        leases[path] = (leases[path] ?: 0) + 1
        var closed = false
        return Closeable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    val remaining = (leases[path] ?: 1) - 1
                    if (remaining <= 0) leases.remove(path) else leases[path] = remaining
                }
            }
        }
    }

    fun deleteTemporary(file: File) {
        val parent = file.parentFile
        if (parent?.parentFile != File(root, TEMPORARY)) return
        if (safeManagedDirectory(parent)) withdraw(parent)?.let(::safeDelete)
    }

    fun safeManagedDirectory(directory: File): Boolean = runCatching {
        val relative = directory.relativeTo(root).path
        relative != ".." && !relative.startsWith("../") && directory.canonicalFile == File(root.canonicalFile, relative)
    }.getOrDefault(false)

    /** 短锁内原子移出可见目录；目录遍历和物理删除始终在租约锁外进行。 */
    private fun withdraw(file: File): File? = synchronized(this) {
        if (isProtected(file) || (!file.exists() && !isSymbolicLink(file))) return@synchronized null
        val prefix = if (file.parentFile == root) ROOT_TRASH_PREFIX else ".trash-"
        val trash = File(file.parentFile, "$prefix${UUID.randomUUID()}")
        if (file.renameTo(trash)) trash else null
    }

    fun statistics(): Statistics = measure(entries())

    /** 分类清理不触发其他目录的自动淘汰，继续遵守下载与外部预览租约。 */
    fun clearCategory(category: String): Statistics = maintenanceLock.withLock {
        require(category in listOf(PREVIEWS, TEMPORARY, IMAGES, UPDATES))
        synchronized(this) { categoryEpochs[category] = (categoryEpochs[category] ?: 0) + 1 }
        entries().filter { it.category == category }.forEach { entry ->
            val retired = withdraw(entry.file)
            if (retired != null) {
                safeDelete(retired)
            } else if (entry.file.isDirectory && safeManagedDirectory(entry.file)) {
                File(entry.file, ".evict").writeText("")
            }
        }
        measure(entries())
    }

    fun removeGateway(gatewayId: String) = maintenanceLock.withLock {
        synchronized(this) { epoch += 1 }
        entries().filter { entry ->
            if (entry.category == IMAGES) {
                entry.file.parentFile?.name == gatewayId
            } else {
                entry.category == PREVIEWS &&
                    runCatching {
                        File(entry.file, "gateway").takeIf { it.length() <= 256 && !isSymbolicLink(it) }?.readText()
                    }.getOrNull() == gatewayId
            }
        }.forEach { entry ->
            val retired = withdraw(entry.file)
            if (retired != null) {
                safeDelete(retired)
            } else if (entry.file.isDirectory && safeManagedDirectory(entry.file)) {
                File(entry.file, ".evict").writeText("")
            }
        }
    }

    /** 启动、进入后台、写入后及用户清空统一经过此处。租约优先于容量限制。 */
    fun maintain(clear: Boolean = false): Statistics = maintenanceLock.withLock {
        if (clear) synchronized(this) { epoch += 1 }
        val entries = entries().toMutableList()
        fun delete(entry: Entry) {
            val retired = withdraw(entry.file) ?: return
            // 从可见目录移走即释放预算，物理删除失败的 .trash 会被下次维护继续回收。
            if (safeDelete(retired)) entries.remove(entry)
        }
        entries.toList().forEach { entry ->
            val abandoned = entry.category == TEMPORARY ||
                entry.file.name.startsWith(".pending-") ||
                entry.file.name.startsWith(".trash-") ||
                entry.file.name.startsWith(ROOT_TRASH_PREFIX) ||
                entry.file.name.endsWith(".tmp") ||
                File(entry.file, ".evict").exists() ||
                (entry.category == UPDATES && (File(entry.file, "update.part").exists() || now() - entry.modifiedAt > UPDATE_TTL_MILLIS))
            val expiredImage = entry.category == IMAGES && now() - entry.modifiedAt >= IMAGE_TTL_MILLIS
            if (clear && isProtected(entry.file) && entry.file.isDirectory && !isSymbolicLink(entry.file)) {
                File(entry.file, ".evict").writeText("")
            }
            if (clear || abandoned || expiredImage) delete(entry)
        }
        fun trim(category: String, maximum: Long, target: Long) {
            var bytes = entries.filter { it.category == category }.sumOf(Entry::bytes)
            if (bytes <= maximum) return
            entries.filter { it.category == category }.sortedBy(Entry::modifiedAt).forEach { entry ->
                if (bytes > target && !isProtected(entry.file)) {
                    delete(entry)
                    if (entry !in entries) bytes -= entry.bytes
                }
            }
        }
        trim(PREVIEWS, limits.previewMaximumBytes, limits.previewTargetBytes)
        trim(IMAGES, limits.imageMaximumBytes, limits.imageTargetBytes)
        trim(TEMPORARY, limits.temporaryMaximumBytes, 0)
        var total = entries.sumOf(Entry::bytes) + databaseBytes()
        val shortage = (limits.minimumFreeBytes - availableBytes()).coerceAtLeast(0)
        val target = if (total > limits.maximumBytes) limits.targetBytes else total
        var reclaim = maxOf(total - target, shortage)
        entries.sortedBy(Entry::modifiedAt).forEach { entry ->
            if (reclaim > 0 && !isProtected(entry.file)) {
                delete(entry)
                if (entry !in entries) {
                    reclaim -= entry.bytes
                    total -= entry.bytes
                }
            }
        }
        measure(entries()).also {
            log(
                "disk-maintenance totalBytes=${it.totalBytes} protectedBytes=${it.protectedBytes} " +
                    "freeBytes=${it.freeBytes} pressureUnresolved=${it.pressureUnresolved}"
            )
        }
    }

    @Synchronized
    fun canRemove(file: File): Boolean = !isProtected(file)

    fun canWriteTemporary(byteCount: Long): Boolean = availableBytes() - byteCount >= limits.minimumFreeBytes

    fun cacheablePreview(byteCount: Long): Boolean = byteCount <= limits.previewTargetBytes &&
        availableBytes() - byteCount >= limits.minimumFreeBytes

    private fun measure(entries: List<Entry>): Statistics {
        val previews = entries.filter { it.category == PREVIEWS }.sumOf(Entry::bytes)
        val images = entries.filter { it.category == IMAGES }.sumOf(Entry::bytes)
        val temporary = entries.filter { it.category == TEMPORARY }.sumOf(Entry::bytes)
        val updates = entries.filter { it.category == UPDATES }.sumOf(Entry::bytes)
        val chat = databaseBytes()
        val free = availableBytes()
        return Statistics(
            previews,
            images,
            temporary,
            updates,
            chat,
            entries.filter { isProtected(it.file) }.sumOf(Entry::bytes),
            free,
            previews + images + temporary + updates + chat > limits.maximumBytes ||
                previews > limits.previewMaximumBytes ||
                images > limits.imageMaximumBytes ||
                temporary > limits.temporaryMaximumBytes ||
                free < limits.minimumFreeBytes
        )
    }

    private fun entries(): List<Entry> = listOf(PREVIEWS, TEMPORARY).flatMap { category ->
        File(root, category).takeUnless(::isSymbolicLink)?.listFiles().orEmpty().map { Entry(it, category, size(it), it.lastModified()) }
    } + descendants(File(root, IMAGES)).filter { it.isFile || isSymbolicLink(it) }.map {
        Entry(it, IMAGES, size(it), it.lastModified())
    } + File(root, UPDATES).takeIf { it.isDirectory && !isSymbolicLink(it) }?.let {
        listOf(Entry(it, UPDATES, size(it), it.lastModified()))
    }.orEmpty() + root.listFiles().orEmpty().filter { it.name.startsWith(ROOT_TRASH_PREFIX) }.map {
        Entry(it, UPDATES, size(it), it.lastModified())
    }

    private fun isProtected(file: File): Boolean {
        val directory = if (file.isDirectory) file else file.parentFile
        val externalUntil = directory?.let { parent ->
            runCatching {
                File(parent, ".lease-until").takeIf { it.length() <= 32 && !isSymbolicLink(it) }?.readText()?.toLong()
            }.getOrNull()
        }
        if (externalUntil != null && externalUntil > now() && externalUntil <= now() + EXTERNAL_LEASE_MILLIS) return true
        return synchronized(this) { leases.keys.toList() }.any { path ->
            path == file.absolutePath ||
                path.startsWith(file.absolutePath + File.separator) ||
                file.absolutePath.startsWith(path + File.separator)
        }
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        file.canonicalFile != File(file.parentFile?.canonicalFile, file.name)
    }.getOrDefault(true)

    private fun descendants(file: File): List<File> = buildList {
        if (!file.exists() && !isSymbolicLink(file)) return@buildList
        add(file)
        if (file.isDirectory && !isSymbolicLink(file)) file.listFiles().orEmpty().forEach { addAll(descendants(it)) }
    }

    private fun size(file: File): Long = descendants(file).filter {
        !isSymbolicLink(it)
    }.sumOf(allocatedBytes)

    private fun safeDelete(file: File): Boolean = runCatching {
        descendants(file).asReversed().forEach { check(it.delete() || !it.exists()) }
        !file.exists()
    }.getOrDefault(false)

    companion object {
        const val MIB = 1024L * 1024
        const val PREVIEWS = "workspace-previews"
        const val TEMPORARY = "workspace-files"
        const val IMAGES = "gateway-image-attachments"
        const val UPDATES = "updates"
        private const val ROOT_TRASH_PREFIX = ".dsh-cache-trash-"
        private const val UPDATE_TTL_MILLIS = 2L * 24 * 60 * 60 * 1000
        fun safeFileName(name: String): String = File(name).name.takeUnless {
            it.isBlank() || it in setOf(".", "..", "sha256", "gateway", ".lease-until", ".evict")
        } ?: "file"

        private const val EXTERNAL_LEASE_MILLIS = 6L * 60 * 60 * 1000
        private const val IMAGE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
