package com.clarklevis.dsh.android.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.espresso.Espresso.onView
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.instanceOf
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MarkdownSelectionTableDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun tablesRenderAsSelectableCellsAndThemeChangesKeepSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("selection-table-test", android.content.Context.MODE_PRIVATE)
        val appearance = AppearanceSettings(preferences).apply { selectInterfaceStyle(InterfaceStyle.LIGHT) }
        val source = "前文 **粗体** 😀\n\n| 品牌 | 结论 |\n| --- | --- |\n| 中文 | **清晰** `code` |\n| 第二行 | 可选择文字 |\n\n后文\n\n```python\n  print('😀')\n```"
        val wideHeader = (1..18).joinToString(" | ") { "列$it" }
        val wideRule = (1..18).joinToString(" | ") { "---" }
        val wideRow = (1..18).joinToString(" | ") { "内容$it" }
        val tableSource = "$source\n\n| $wideHeader |\n| $wideRule |\n| $wideRow |"
        assertTrue(markdownHasSelectionTable(tableSource))
        assertFalse(markdownHasSelectionTable("```md\n| a | b |\n| --- | --- |\n```"))
        try {
            compose.setContent { DshTheme(appearance) { DshMarkdownSelectionPage(tableSource) {} } }
            compose.onNodeWithTag("markdown-table-text-selection").assertIsDisplayed()
            var web: DshMenuWebView? = null
            onView(instanceOf(DshMenuWebView::class.java)).check { view, error ->
                if (error != null) throw error
                web = view as DshMenuWebView
            }
            val browser = checkNotNull(web)
            fun js(script: String): String {
                val latch = CountDownLatch(1)
                var result = ""
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    browser.evaluateJavascript(script) { result = it; latch.countDown() }
                }
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                return result
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (js("window.dshReady === true") != "true" && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("2", js("document.querySelectorAll('table').length"))
            val metrics = JSONArray(js("(()=>{const t=document.querySelector('table');return [t.rows[1].cells[0].offsetWidth,t.offsetWidth,t.parentElement.clientWidth,getComputedStyle(t.rows[1].cells[0]).borderBottomStyle];})()"))
            assertTrue(metrics.getInt(0) >= 96)
            assertEquals(metrics.getDouble(1), metrics.getDouble(2), 2.0)
            assertEquals("solid", metrics.getString(3))
            val wideMetrics = JSONArray(js("(()=>{const t=document.querySelectorAll('table')[1],w=t.parentElement;w.scrollLeft=100;return [t.offsetWidth>w.clientWidth,w.scrollLeft,document.documentElement.scrollWidth<=innerWidth];})()"))
            assertTrue(wideMetrics.getBoolean(0))
            assertTrue(wideMetrics.getInt(1) > 0)
            assertTrue(wideMetrics.getBoolean(2))
            js("window.dshSelectAll()")
            val selection = js("window.dshSelectedText()")
            assertTrue(selection.contains("中文")); assertTrue(selection.contains("清晰"))
            assertTrue(selection.contains("前文")); assertTrue(selection.contains("后文"))
            assertFalse(selection.contains("**")); assertFalse(selection.contains("---"))
            compose.runOnIdle { appearance.selectInterfaceStyle(InterfaceStyle.DARK) }
            compose.waitForIdle()
            assertEquals(selection, js("window.dshSelectedText()"))
            assertEquals("\"dark\"", js("document.documentElement.dataset.theme"))
            val frameReady = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                browser.postVisualStateCallback(1, object : android.webkit.WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { frameReady.countDown() }
                })
            }
            assertTrue(frameReady.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            android.os.SystemClock.sleep(300)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            context.cacheDir.resolve("selection-table-android.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            val cellPoint = JSONArray(js("(()=>{const r=document.querySelector('td').getBoundingClientRect();return [r.left+20,r.top+20,innerWidth];})()"))
            onView(instanceOf(DshMenuWebView::class.java)).perform(androidx.test.espresso.action.GeneralClickAction(
                androidx.test.espresso.action.Tap.LONG,
                androidx.test.espresso.action.CoordinatesProvider { view ->
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    val scale = view.width / cellPoint.getDouble(2)
                    floatArrayOf((location[0] + cellPoint.getDouble(0) * scale).toFloat(),
                        (location[1] + cellPoint.getDouble(1) * scale).toFloat())
                }, androidx.test.espresso.action.Press.FINGER))
            compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("复制")).assertIsDisplayed()
            val chosen = org.json.JSONTokener(js("window.dshSelectedText()")).nextValue() as String
            assertTrue(chosen.isNotEmpty())
            compose.onNodeWithText(com.clarklevis.dsh.android.localization.appText("复制")).performClick()
            compose.waitForIdle()
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == chosen }
        } finally {
            preferences.edit().clear().apply()
        }
    }
}
