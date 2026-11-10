package com.clarklevis.dsh.android.update

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    @Test fun skipOnlyMatchesTheChosenVersion() {
        assertTrue(isSkippedVersion("v1.8.0", "1.8.0"))
        assertFalse(isSkippedVersion("v1.8.1", "1.8.0"))
        assertFalse(isSkippedVersion("v1.8.0", null))
    }

    @Test fun comparesNumericVersions() {
        assertTrue(compareVersions("v1.10.0", "1.9.9") > 0)
        assertEquals(0, compareVersions("v1.7.2", "1.7.2"))
        assertTrue(compareVersions("1.7.1", "1.7.2") < 0)
    }

    // P0-AT-05（2026-11-11 分家）：更新源只认用户配置的干净 HTTPS URL；
    // 归一化不通过 = 「未配置」= 调用方零联网，不得回退任何内置源。
    @Test fun updateSourceRequiresHttpsAndCleanUrl() {
        assertNull(normalizeUpdateSourceUrl(null))
        assertNull(normalizeUpdateSourceUrl("   "))
        assertNull(normalizeUpdateSourceUrl("not a url"))
        assertNull(normalizeUpdateSourceUrl("http://updates.own.example/latest.json"))
        assertNull(normalizeUpdateSourceUrl("https://user:pass@updates.own.example/latest.json"))
        assertEquals("https://updates.own.example/latest.json",
            normalizeUpdateSourceUrl("  https://updates.own.example/latest.json  "))
    }

    /**
     * 更新源存取语义（Chief 2026-11-11 派单：设置项必须有地方填）。
     * JVM 没有 SharedPreferences（无 Robolectric），故对存取的**纯逻辑层**——
     * normalizeUpdateSourceUrl 契约——做穷举；ViewModel 落盘层由真机复核
     * （AndroidTester：保存合法地址 → 行值更新；保存非法 → 行值不变 + 错误提示）。
     * 合法输入被 normalize 接受 ⇔ saveUpdateSource 会落盘（二者共用同一函数，唯一判据）。
     */
    @Test fun updateSourceContractAcceptsOnlyHttpsAndCleanUrl() {
        // saveUpdateSource 落盘 ⇔ normalizeUpdateSourceUrl(raw) != null（同一函数判定）
        val acceptable = listOf(
            "https://updates.own.example/latest.json",
            "  https://updates.own.example/latest.json  ",
            "https://updates.own.example:8443/p/latest.json"
        )
        acceptable.forEach { raw ->
            assertNotNull("valid input must be storable: $raw", normalizeUpdateSourceUrl(raw))
        }
        val rejected = listOf(null, "", "   ", "not a url",
            "http://updates.own.example/latest.json",
            "https://user:pass@updates.own.example/latest.json",
            "ftp://updates.own.example/latest.json")
        rejected.forEach { raw ->
            assertNull("invalid input must be rejected: $raw", normalizeUpdateSourceUrl(raw))
        }
    }
}
