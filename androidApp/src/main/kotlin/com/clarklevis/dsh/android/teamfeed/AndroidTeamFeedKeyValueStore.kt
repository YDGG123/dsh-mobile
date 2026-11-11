package com.clarklevis.dsh.android.teamfeed

import android.content.Context
import com.clarklevis.dsh.shared.teamfeed.TeamFeedKeyValueStore
import java.io.File
import java.security.MessageDigest

/**
 * vc61 ⑥：KMP `TeamFeedKeyValueStore` 的安卓文件实现（`TeamRosterCache` 水合的落盘层）。
 * 目录 `filesDir/teamfeed-kv/<gateway>/<key>`；写 = 临时文件 + rename（原子），rename 失败退化直写。
 * 读写本身同步（几百 KB 很快，iOS `TeamRosterCache.swift:19` 同口径）——
 * **回写在后台由调用方保证**（红线：别在主线程写盘）。
 *
 * roster、seq 与 resume instance 都是主机私有状态。旧 `legacy` 目录仅为兼容默认构造保留；
 * 真正的多主机实例使用独立目录，不迁移旧缓存，切主机后必须重新取快照。
 */
internal class AndroidTeamFeedKeyValueStore(
    context: Context,
    gatewayLocalId: String = "legacy",
) : TeamFeedKeyValueStore {
    private val dir = File(context.applicationContext.filesDir, "teamfeed-kv")
        .let { root ->
            if (gatewayLocalId == "legacy") root
            else File(root, "gateway-${gatewayLocalId.sha256().take(24)}")
        }
        .apply { mkdirs() }

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

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
