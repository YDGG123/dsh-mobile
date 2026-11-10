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
}
