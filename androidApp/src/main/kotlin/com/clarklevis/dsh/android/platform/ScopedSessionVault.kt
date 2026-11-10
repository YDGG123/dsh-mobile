package com.clarklevis.dsh.android.platform

/**
 * Bridge 设备会话「按主机作用域」保险箱（P0-AT-02，Chief 2026-11-11 派单）。
 *
 * 键策略（产线与 JVM 测试共用这一份逻辑）：
 * - 只读写 scoped key `bridge_session_v2:<gatewayLocalId>`，两台主机的 Bearer/refresh 互不可见；
 * - 分家前的旧全局 key `bridge_session_v1` 由**第一台**读它的主机原子认领
 *   （同一 commit：写 scoped + 删全局）——第二台主机拿不到同一份旧凭证；
 * - [clear] 同时删两把 key，防止已判死的会话在下一次「迁移」里复活。
 *
 * 存储与加解密走注入接口：产线 = SharedPreferences + AndroidKeyStore；
 * 测试 = 内存 Map + 可逆 fake（JVM 没有 AndroidKeyStore）。
 * 调用方须自行串行化（产线 [AndroidBridgeSessionStore] 的 lock）。
 */
internal class ScopedSessionVault(
    private val kv: SessionKv,
    private val crypto: SessionCrypto,
    gatewayLocalId: String,
) {
    val scopedKey: String = "${Companion.KEY_SESSION_PREFIX}:$gatewayLocalId"

    /** true = 磁盘上有 bridge 会话密文但解不开：上层应提示「需要重新配对」。 */
    @Volatile var restoreFailed: Boolean = false
        private set

    fun loadJson(): String? {
        kv.getString(scopedKey)?.let { encoded -> return decryptOrFlag(encoded) }
        // 旧版本全 App 只有一份会话。第一次按主机读取时原子认领并删除旧 key，
        // 避免第二台主机再把同一份 Bearer/refresh token 恢复出来。
        val legacy = kv.getString(Companion.KEY_SESSION) ?: return null
        val claimed = kv.putAndRemove(scopedKey, legacy, Companion.KEY_SESSION)
        return legacy.takeIf { claimed }?.let(::decryptOrFlag)
    }

    fun saveJson(json: String) {
        if (json.isBlank()) return
        runCatching { crypto.encrypt(json) }.getOrNull()?.let { encoded ->
            kv.putAndRemove(scopedKey, encoded, Companion.KEY_SESSION)
        }
    }

    fun clear() {
        kv.remove(scopedKey)
        kv.remove(Companion.KEY_SESSION)
    }

    private fun decryptOrFlag(encoded: String): String? =
        runCatching { crypto.decrypt(encoded) }.getOrElse {
            // **非销毁式失败**（iOSCoder 2026-11-02 定性）：一次 Keystore 不可用就把会话密文删掉，
            // 等于把瞬时故障变成"必须重新扫码"。保留密文 + 标记，由上层提示重新配对。
            restoreFailed = true
            null
        }

    companion object {
        const val KEY_SESSION = "bridge_session_v1"
        const val KEY_SESSION_PREFIX = "bridge_session_v2"
    }
}

/** 键值存储抽象：产线实现包 SharedPreferences（同步 commit 语义）。 */
internal interface SessionKv {
    fun getString(key: String): String?
    /** 单次原子写入 + 删除另一把 key；false = 落盘失败（调用方按未认领处理）。 */
    fun putAndRemove(key: String, value: String, removedKey: String): Boolean
    fun remove(key: String): Boolean
}

/** 会话密文加解密抽象：产线 = AndroidKeyStore AES-GCM（密钥不出 TEE）。 */
internal interface SessionCrypto {
    fun encrypt(value: String): String
    fun decrypt(encoded: String): String
}
