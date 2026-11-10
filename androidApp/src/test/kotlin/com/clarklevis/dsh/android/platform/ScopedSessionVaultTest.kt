package com.clarklevis.dsh.android.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-AT-02 安卓侧回归：bridge 设备会话按主机作用域（Chief 2026-11-11 派单）。
 * 覆盖：legacy 全局 key 的原子认领（只发生一次）、A/B 双主机互不可见、
 * clear 不串台且不让 legacy 复活、解密失败保留密文并置 restoreFailed。
 * JUnit4 断言参数序：message 在前（FIXLOG 已录的同族教训）。
 */
class ScopedSessionVaultTest {

    /** 内存 KV；可注入 commit 失败（模拟磁盘满等落盘失败）。 */
    private class FakeKv : SessionKv {
        val map = LinkedHashMap<String, String>()
        var failNextPut = false
        override fun getString(key: String): String? = map[key]
        override fun putAndRemove(key: String, value: String, removedKey: String): Boolean {
            if (failNextPut) { failNextPut = false; return false }
            map[key] = value
            map.remove(removedKey)
            return true
        }
        override fun remove(key: String): Boolean = map.remove(key) != null
    }

    /** 可逆 fake 加解密：`enc:<reverse>`；可注入解密失败（模拟 Keystore 重置）。 */
    private class FakeCrypto(var failDecrypt: Boolean = false) : SessionCrypto {
        override fun encrypt(value: String): String = "enc:" + value.reversed()
        override fun decrypt(encoded: String): String {
            if (failDecrypt) throw IllegalStateException("keystore reset")
            return encoded.removePrefix("enc:").reversed()
        }
    }

    private fun vault(kv: FakeKv, crypto: FakeCrypto, gateway: String) =
        ScopedSessionVault(kv, crypto, gateway)

    private fun scopedKey(gateway: String) = "${ScopedSessionVault.KEY_SESSION_PREFIX}:$gateway"

    /** FakeCrypto 的密文格式：`enc:<reverse>`——预置磁盘数据时必须用它，不能写明文。 */
    private fun enc(value: String) = "enc:" + value.reversed()

    // ---------- legacy 全局 key 认领 ----------

    @Test fun firstHostClaimsLegacyAtomicallyAndOnce() {
        val kv = FakeKv().apply { map[ScopedSessionVault.KEY_SESSION] = enc("legacy-session") }
        val a = vault(kv, FakeCrypto(), "gw-a")
        assertEquals("legacy-session", a.loadJson())
        assertNull("legacy key must be gone after claim",
            kv.map[ScopedSessionVault.KEY_SESSION])
        assertEquals("claimed session must live under the scoped key",
            enc("legacy-session"), kv.map[scopedKey("gw-a")])
        assertEquals("second read goes through the scoped key", "legacy-session", a.loadJson())
    }

    @Test fun secondHostCannotClaimClaimedLegacy() {
        val kv = FakeKv().apply { map[ScopedSessionVault.KEY_SESSION] = enc("legacy-session") }
        assertEquals("legacy-session", vault(kv, FakeCrypto(), "gw-a").loadJson())
        assertNull("second host must not see the claimed legacy session",
            vault(kv, FakeCrypto(), "gw-b").loadJson())
    }

    @Test fun failedClaimKeepsLegacyAndHandsOutNothing() {
        val kv = FakeKv().apply {
            map[ScopedSessionVault.KEY_SESSION] = enc("legacy-session")
            failNextPut = true
        }
        val a = vault(kv, FakeCrypto(), "gw-a")
        assertNull("failed commit must not hand out plaintext", a.loadJson())
        assertEquals("legacy key must survive a failed claim",
            enc("legacy-session"), kv.map[ScopedSessionVault.KEY_SESSION])
        assertNull(kv.map[scopedKey("gw-a")])
    }

    // ---------- A/B 双主机隔离 ----------

    @Test fun twoHostsKeepIndependentSessions() {
        val kv = FakeKv()
        val crypto = FakeCrypto()
        val a = vault(kv, crypto, "gw-a")
        val b = vault(kv, crypto, "gw-b")
        a.saveJson("""{"token":"A"}""")
        b.saveJson("""{"token":"B"}""")
        assertEquals("""{"token":"A"}""", a.loadJson())
        assertEquals("""{"token":"B"}""", b.loadJson())
    }

    @Test fun clearingOneHostDoesNotTouchTheOther() {
        val kv = FakeKv()
        val crypto = FakeCrypto()
        val a = vault(kv, crypto, "gw-a")
        val b = vault(kv, crypto, "gw-b")
        a.saveJson("json-a")
        b.saveJson("json-b")
        b.clear()
        assertEquals("un-pairing B must not erase A", "json-a", a.loadJson())
        assertNull(b.loadJson())
    }

    @Test fun clearAlsoRemovesLegacySoItCannotRevive() {
        val kv = FakeKv().apply { map[ScopedSessionVault.KEY_SESSION] = enc("legacy") }
        val a = vault(kv, FakeCrypto(), "gw-a")
        a.clear()
        assertNull(a.loadJson())
        assertFalse("cleared legacy key must not come back",
            kv.map.containsKey(ScopedSessionVault.KEY_SESSION))
    }

    // ---------- 解密失败（fail-closed 非销毁式） ----------

    @Test fun decryptFailureKeepsCiphertextAndFlagsRestoreFailed() {
        val key = scopedKey("gw-a")
        val kv = FakeKv().apply { map[key] = enc("junk") }
        val a = vault(kv, FakeCrypto(failDecrypt = true), "gw-a")
        assertNull(a.loadJson())
        assertTrue("restoreFailed must be flagged", a.restoreFailed)
        assertEquals("ciphertext must be kept for re-pairing diagnosis", enc("junk"), kv.map[key])
    }

    @Test fun blankSaveIsIgnored() {
        val kv = FakeKv()
        vault(kv, FakeCrypto(), "gw-a").saveJson("   ")
        assertTrue(kv.map.isEmpty())
    }
}
