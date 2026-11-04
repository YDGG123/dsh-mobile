package com.clarklevis.dsh.android.teamfeed

import android.content.Context
import com.clarklevis.dsh.shared.teamfeed.TeamFeedKeyValueStore
import java.io.File

/**
 * vc61 ⑥：KMP `TeamFeedKeyValueStore` 的安卓文件实现（`TeamRosterCache` 水合的落盘层）。
 * 目录 `filesDir/teamfeed-kv/<key>`；写 = 临时文件 + rename（原子），rename 失败退化直写。
 * 读写本身同步（几百 KB 很快，iOS `TeamRosterCache.swift:19` 同口径）——
 * **回写在后台由调用方保证**（红线：别在主线程写盘）。
 */
internal class AndroidTeamFeedKeyValueStore(context: Context) : TeamFeedKeyValueStore {
    private val dir = File(context.applicationContext.filesDir, "teamfeed-kv").apply { mkdirs() }

    override fun get(key: String): String? =
        runCatching { File(dir, key.safeFileName()).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun put(key: String, value: String) {
        runCatching {
            val target = File(dir, key.safeFileName())
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeText(value)
            if (!tmp.renameTo(target)) {
                target.writeText(value)
                tmp.delete()
            }
        }
    }

    override fun remove(key: String) {
        runCatching { File(dir, key.safeFileName()).delete() }
    }

    /** key 是 KMP 常量（team-feed-roster-v1 等）；防御性清洗，防将来 key 混进路径分隔符。 */
    private fun String.safeFileName(): String =
        filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "key" }
}
