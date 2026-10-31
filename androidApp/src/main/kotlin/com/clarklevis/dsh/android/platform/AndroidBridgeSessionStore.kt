package com.clarklevis.dsh.android.platform

import android.content.Context
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
 * - 解密失败（Keystore 重置、密文损坏）→ 删除该条并按「未配对」处理（fail-closed）。
 */
internal class AndroidBridgeSessionStore(context: Context) : BridgeSessionStore {
    private val appContext = context.applicationContext
    private val lock = Any()

    private fun preferences() = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun loadJson(): String? = synchronized(lock) {
        val encoded = preferences().getString(KEY_SESSION, null) ?: return null
        runCatching { decrypt(encoded) }.getOrElse {
            preferences().edit().remove(KEY_SESSION).commit()
            null
        }
    }

    override fun saveJson(json: String) {
        if (json.isBlank()) return
        synchronized(lock) {
            val encoded = runCatching { encrypt(json) }.getOrNull() ?: return
            preferences().edit().putString(KEY_SESSION, encoded).commit()
        }
    }

    override fun clear() {
        synchronized(lock) {
            preferences().edit().remove(KEY_SESSION).commit()
        }
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
        private const val KEY_SESSION = "bridge_session_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "dsh_bridge_session_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val AAD = "bridge_device_session"
        private const val VERSION: Byte = 1
    }
}
