package com.clarklevis.dsh.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 版本自证串（缺陷登记表 #12）：界面显示 `1.7.13 (50)`。
 *
 * 这条不测"比较逻辑"（比较在 `AppUpdateViewModel.compareVersions` 与 `SettingsScreen` 里，
 * 必须继续吃**原始 versionName**），只钉住两件事：
 * ① 显示串的格式；② 它**只是**一个独立串 —— 谁都不许把它当成比较输入用
 *    （iOS `:429` 那个坑：拼进去「检查更新」和「本版本新功能」一起坏）。
 */
class DisplayVersionTest {

    @Test
    fun displayStringCarriesVersionCode() {
        assertEquals("1.7.13 (50)", formatDisplayVersion("1.7.13", 50, "未知"))
        assertEquals("2.0.0 (7)", formatDisplayVersion("2.0.0", 7, "未知"))
    }

    /** 拿不到 versionName 时给「未知」，不拼半截串。 */
    @Test
    fun missingVersionNameFallsBackToUnknownLabel() {
        assertEquals("未知", formatDisplayVersion(null, 50, "未知"))
        assertEquals("未知", formatDisplayVersion("", 50, "未知"))
        assertEquals("未知", formatDisplayVersion("   ", 50, "未知"))
    }

    /** 显示串可以被解析回 versionCode，但**原始版本名仍然是纯值**（比较用的那一份没被污染）。 */
    @Test
    fun displayStringIsDerivedAndRawStaysPure() {
        val raw = "1.7.13"
        val display = formatDisplayVersion(raw, 50, "未知")
        assertEquals("1.7.13", raw)
        assertFalse("原始版本名不该被改写", raw.contains("("))
        assertEquals("1.7.13 (50)", display)
    }
}