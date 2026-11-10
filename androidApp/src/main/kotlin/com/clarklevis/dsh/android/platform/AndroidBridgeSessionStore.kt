package com.clarklevis.dsh.android.platform

import android.content.Context
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.clarklevis.dsh.shared.domain.BridgeSessionStore
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mobile Bridge 设备会话落盘（等价 iOS `BridgeKeychainSessionStore`）。
 *
 * - 明文只在内存里出现：AES-GCM（AndroidKeyStore 托管密钥）加密后写入独立 no-backup 文件；
 * - [BridgeSessionStore] 是同步接口（KMP 状态机直接调用），故用 SharedPreferences 而非 DataStore；
 * - 解密失败（Keystore 重置、密文损坏）→ 保留密文并置 [restoreFailed]（fail-closed，不删）；
 * - 按主机作用域与旧全局 key 的原子认领逻辑在 [ScopedSessionVault]（P0-AT-02，测试直接打它）。
 */
internal class AndroidBridgeSessionStore(
    context: Context,
    gatewayLocalId: String = LEGACY_GATEWAY_ID,
) : BridgeSessionStore {
    private val appContext = context.applicationContext
    private val TAG = "BridgeSessionStore"
    private val lock = Any()
    private val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val vault = ScopedSessionVault(
        kv = object : SessionKv {
            override fun getString(key: String): String? = prefs.getString(key, null)
            override fun putAndRemove(key: String, value: String, removedKey: String): Boolean =
                prefs.edit().putString(key, value).remove(removedKey).commit()
            override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()
        },
        crypto = object : SessionCrypto {
            override fun encrypt(value: String): String = this@AndroidBridgeSessionStore.encrypt(value)
            override fun decrypt(encoded: String): String = this@AndroidBridgeSessionStore.decrypt(encoded)
        },
        gatewayLocalId = gatewayLocalId,
    )

    /** prefs 文件是否真的在磁盘上（覆盖安装排查：文件在但读不到 = 更早的问题）。 */
    private fun prefsFileExists(): Boolean = runCatching {
        java.io.File(appContext.applicationInfo.dataDir, "shared_prefs/$FILE_NAME.xml").exists()
    }.getOrDefault(false)

    override fun loadJson(): String? = synchronized(lock) {
        val hadScoped = prefs.getString(vault.scopedKey, null) != null
        val hadLegacy = prefs.getString(ScopedSessionVault.KEY_SESSION, null) != null
        vault.loadJson().also { json ->
            when {
                json != null -> Log.i(TAG, "loadJson: ciphertext ${json.length}B")
                vault.restoreFailed -> Log.w(TAG, "bridge session decrypt failed; keep ciphertext")
                else -> // 覆盖安装排查用：区分「密文不存在」与「密文在但解不开」（后者由上一条日志报）。
                    Log.i(TAG, "loadJson: no ciphertext (prefs=$FILE_NAME, fileExists=${prefsFileExists()}, hadScoped=$hadScoped, hadLegacy=$hadLegacy)")
            }
        }
    }

    /** true = 磁盘上有 bridge 会话密文但解不开：上层应提示「需要重新配对」。 */
    val restoreFailed: Boolean get() = vault.restoreFailed

    override fun saveJson(json: String) {
        synchronized(lock) { vault.saveJson(json) }
    }

    override fun clear() {
        synchronized(lock) { vault.clear() }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
            .put(VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        require(buffer.get() == VERSION) { "unsupported bridge session ciphertext version" }
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..32 && buffer.remaining() > ivLength) { "invalid bridge session ciphertext" }
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val FILE_NAME = "bridge_session.preferences_pb"
        private const val LEGACY_GATEWAY_ID = "legacy"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "dsh_bridge_session_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val AAD = "bridge_device_session"
        private const val VERSION: Byte = 1
    }
}
