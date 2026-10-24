package com.clarklevis.dsh.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class DshMenuDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun webViewUsesProjectTextMenuAndKeepsBrowserCopyAction() {
        var loaded by mutableStateOf(false)
        var wordCenter: androidx.compose.ui.geometry.Offset? = null
        var request by mutableStateOf<DshNativeTextMenu?>(null)
        compose.setContent { DshTheme {
            Box {
                androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                    DshMenuWebView(context).apply {
                        onMenuChanged = { request = it }
                        settings.javaScriptEnabled = true
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                                view.postVisualStateCallback(1, object : android.webkit.WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        view.evaluateJavascript("JSON.stringify((()=>{const r=document.querySelector('div').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,devicePixelRatio]})())") { result ->
                                            val values = org.json.JSONArray(org.json.JSONTokener(result).nextValue() as String)
                                            val scale = values.getDouble(2).toFloat()
                                            wordCenter = androidx.compose.ui.geometry.Offset(values.getDouble(0).toFloat() * scale, values.getDouble(1).toFloat() * scale)
                                            loaded = true
                                        }
                                    }
                                })
                            }
                        }
                        loadData("<html><head><meta name='viewport' content='width=device-width, initial-scale=1'></head>" +
                            "<body style='margin:0'><div style='position:absolute;top:240px;left:24px;font-size:28px;line-height:40px'>nativeword</div></body></html>",
                            "text/html", "UTF-8")
                    }
                }, onRelease = { it.dismissTextMenu(); it.onMenuChanged = null; it.destroy() },
                    modifier = Modifier.fillMaxSize().testTag("native-web"))
                request?.let { menu ->
                    DshTextActions(androidx.compose.ui.geometry.Rect(menu.bounds.left.toFloat(), menu.bounds.top.toFloat(),
                        menu.bounds.right.toFloat(), menu.bounds.bottom.toFloat()), menu.dismiss, menu.actions)
                }
            }
        } }
        compose.waitUntil(10_000) { loaded }
        // Chromium 的长按计时依赖真实消息循环，不能使用 Compose 的虚拟事件时间。
        androidx.test.espresso.Espresso.onView(org.hamcrest.Matchers.instanceOf(DshMenuWebView::class.java)).perform(
            androidx.test.espresso.action.GeneralClickAction(
                androidx.test.espresso.action.Tap.LONG,
                { view ->
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    val center = checkNotNull(wordCenter)
                    floatArrayOf(location[0] + center.x, location[1] + center.y)
                },
                androidx.test.espresso.action.Press.FINGER,
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
        )
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("dsh-text-menu")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("复制").performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle { assertEquals("nativeword", clipboard.primaryClip?.getItemAt(0)?.text.toString()) }
    }

    @Test fun attachmentMenuUsesProjectSurfaceAndOpensAndDismisses() {
        val holder = AndroidSharedStateHolder().apply {
            loadFixture()
            wirePayload = """{"kind":"question-resolved","rpcId":"android-question"}"""
            submitWirePayload()
        }
        try {
            compose.setContent { DshTheme { ConversationScreen(holder, onPickImage = {}, onBack = {}) } }
            compose.onNodeWithContentDescription("添加附件").performClick()
            compose.onNodeWithText("图片").assertIsDisplayed()
            compose.onNodeWithText("文件").assertIsDisplayed()
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val file = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("unified-attachment-menu.png")
            file.outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screen.recycle()
            androidx.test.espresso.Espresso.pressBack()
            compose.waitForIdle()
            compose.onNodeWithText("图片").assertDoesNotExist()
            compose.onNodeWithContentDescription("添加附件").performClick()
            compose.onNodeWithText("文件").assertIsDisplayed()
        } finally { compose.runOnIdle { holder.close() } }
    }

    @Test fun textMenusCopyAndPasteThroughNativeEditor() {
        var value by mutableStateOf(TextFieldValue("first second third"))
        compose.setContent { DshTheme {
            BasicTextField(value, { value = it }, Modifier.testTag("editor"))
        } }
        compose.onNodeWithTag("editor").performTouchInput { longClick() }
        compose.onNodeWithTag("dsh-text-menu").assertIsDisplayed()
        val copied = compose.runOnIdle { value.text.substring(value.selection.min, value.selection.max) }
        compose.onNodeWithText("复制").performClick()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle { assertEquals(copied, clipboard.primaryClip?.getItemAt(0)?.text.toString()) }
        compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().targetContext.copyMarkdownText("replacement")
            value = value.copy(selection = TextRange(0, 5))
        }
        compose.onNodeWithTag("editor").performTouchInput { longClick() }
        compose.onNodeWithTag("dsh-text-menu").assertIsDisplayed()
        val before = compose.runOnIdle { value }
        compose.onNodeWithText("粘贴").performClick()
        compose.runOnIdle {
            assertEquals(before.text.replaceRange(before.selection.min, before.selection.max, "replacement"), value.text)
        }
    }

    @Test fun dialogTextMenuStaysInItsOwnWindow() {
        var value by mutableStateOf(TextFieldValue("dialog selection"))
        compose.setContent { DshTheme {
            DshAlertDialog("窗口测试", {}, content = {
                BasicTextField(value, { value = it }, Modifier.testTag("dialog-editor"))
            })
        } }
        compose.onNodeWithTag("dialog-editor").performTouchInput { longClick() }
        compose.onNodeWithTag("dsh-text-menu").assertIsDisplayed()
        compose.onNodeWithText("全选").performClick()
        compose.runOnIdle { assertEquals(TextRange(0, value.text.length), value.selection) }
        compose.onNodeWithText("窗口测试").assertIsDisplayed()
    }

    @Test fun disabledMenuItemCannotRunAndEnabledItemClosesMenu() {
        var expanded by mutableStateOf(true)
        var invoked = 0
        compose.setContent { DshTheme {
            Box {
                DshMenu(expanded, { expanded = false }) {
                    DshMenuItem(text = { Text("禁用操作") }, enabled = false, onClick = { invoked++ })
                    DshMenuItem(text = { Text("确认操作") }, onClick = { invoked++; expanded = false })
                }
            }
        } }
        compose.onNodeWithText("禁用操作").assertIsNotEnabled()
        assertEquals(0, invoked)
        compose.onNodeWithText("确认操作").performClick()
        compose.runOnIdle { assertEquals(1, invoked); assertFalse(expanded) }
        compose.onNodeWithText("确认操作").assertDoesNotExist()
    }
}
