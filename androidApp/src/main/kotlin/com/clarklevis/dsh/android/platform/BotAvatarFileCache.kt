package com.clarklevis.dsh.android.platform

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bot 头像的**文件缓存**——Android 版 `TeamAvatarFileCache`（iOS Core/TeamAvatarFileCache.swift）。
 *
 * 规格以 iOS **实现**为准（AndroidTester 2026-11-02 回源码核定，注释与实现不一致处以实现为准）：
 * - 缓存键 = 原始字节的 **sha256 前 16 位 hex**（不是 botId / 时间戳 / URL）；
 * - 写入拦 **512KB**（超限不缓存、回符号层）；**同 hash 已存在 → 直接返回，不重写不改 mtime**；
 * - **无 LRU、无 mtime 淘汰**：超 20MB **只记日志、不删**（iOS :140-141 原文「宁可暂时超限」）；
 *   清理只有一种：`prune(keeping:)` 按**当前快照引用的 hash 集合**做差集删除；
 * - 失败一律静默返回 null；日志只报 hash 前缀与字节数，**内容绝不进日志**（脱敏红线）；
 * - 目录不参与备份（可重建缓存）；写盘为同步单线程（Android 侧 `store()` 本身即同步落盘，
 *   天然满足「失去活跃时已落盘」，无需 iOS 那条后台兜底钩子）。
 */
object BotAvatarFileCache {
    private const val TAG = "BotAvatarCache"
    const val MAX_ENTRY_BYTES = 512 * 1024
    const val MAX_TOTAL_BYTES = 20 * 1024 * 1024
    const val HASH_LENGTH = 16
    private const val FILE_EXTENSION = "img"

    @Volatile private var directory: File? = null
    private val mutex = Mutex()
    private val referenced = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 名册更新时登记当前引用的 hash（差集删除的 keeping 集合来源）。 */
    fun remember(hash: String) {
        if (hash.length == HASH_LENGTH) referenced.add(hash)
    }

    fun init(context: Context) {
        if (directory != null) return
        // 目录不参与备份（iOS :188 isExcludedFromBackup 的 Android 等价物）：noBackupFilesDir 天然被
        // auto-backup 排除，可重建缓存不该进云备份。
        directory = File(context.noBackupFilesDir, "bot-avatars").apply { mkdirs() }
    }

    /** data URL（`data:image/png;base64,…`）→ 解出原始字节落盘，返回内容 hash；失败/超限返回 null。 */
    suspend fun store(dataUrl: String): String? {
        val dir = directory ?: return null
        val payload = dataUrl.substringAfter("base64,", "").trim()
        if (payload.isEmpty()) return null
        val bytes = runCatching { android.util.Base64.decode(payload, android.util.Base64.DEFAULT) }
            .getOrNull() ?: return null
        if (bytes.size > MAX_ENTRY_BYTES) {
            Log.w(TAG, "avatar ${bytes.size}B exceeds entry cap, keep symbol layer")
            return null
        }
        val hash = sha256Prefix(bytes)
        mutex.withLock {
            val file = File(dir, "$hash.$FILE_EXTENSION")
            if (file.exists()) return hash // 同 hash 已存在 → 不重写、不改 mtime（iOS :91）
            runCatching { file.writeBytes(bytes) }
                .onFailure { Log.w(TAG, "avatar write failed ${hash.take(6)}") }
                .onSuccess { enforceTotalCap(dir) }
        }
        return hash
    }

    fun fileFor(hash: String): File? {
        if (hash.length != HASH_LENGTH || hash.any { !it.isDigit() && it !in 'a'..'f' }) return null
        val file = File(directory, "$hash.$FILE_EXTENSION")
        return if (file.exists()) file else null
    }

    /** 按当前快照引用的 hash 集合做**差集删除**；正在引用的一个不删（iOS prune(keeping:) 同款）。 */
    suspend fun prune() {
        val dir = directory ?: return
        val keeping = referenced.toSet()
        mutex.withLock {
            dir.listFiles { f -> f.extension == FILE_EXTENSION }?.forEach { file ->
                if (file.nameWithoutExtension !in keeping) runCatching { file.delete() }
            }
        }
    }

    private fun enforceTotalCap(dir: File) {
        // 只统计不删除：与 iOS 一致，删掉在用头像会让 Bot 页退化成符号层（AndroidTester 判据）。
        val total = dir.listFiles { f -> f.extension == FILE_EXTENSION }?.sumOf { it.length() } ?: 0L
        if (total > MAX_TOTAL_BYTES) Log.w(TAG, "avatar cache $total B over cap, keep all in-use files")
    }

    private fun sha256Prefix(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(HASH_LENGTH)
}
