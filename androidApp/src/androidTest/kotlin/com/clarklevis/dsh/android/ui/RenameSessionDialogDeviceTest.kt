package com.clarklevis.dsh.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RenameSessionDialogDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()

    @Test fun longPressCopiesAndPastesInDialogWindowThenSaves() {
        var title by mutableStateOf("Greeting")
        var visible by mutableStateOf(true)
        var saved: String? = null
        compose.setContent {
            DshTheme {
                // 主页面与弹窗同时存在，覆盖旧实现误用主页面菜单宿主的场景。
                Box {
                    BasicTextField("页面输入框", {}, Modifier.testTag("page-editor"))
                    if (visible) {
                        RenameSessionDialog(title, { title = it }, { visible = false }) {
                            saved = title
                            visible = false
                        }
                    }
                }
            }
        }

        val editor = compose.onNodeWithTag("rename-session-title")
        editor.performTouchInput { longClick(Offset(70f, center.y)) }
        compose.onNodeWithTag("dsh-text-menu").assertIsDisplayed()
        compose.onNodeWithText("复制").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle {
            assertEquals("Greeting", clipboard.primaryClip?.getItemAt(0)?.text.toString())
            context.copyMarkdownText("Renamed")
        }

        editor.performTouchInput { longClick(Offset(70f, center.y)) }
        compose.onNodeWithTag("dsh-text-menu").assertIsDisplayed()
        compose.onNodeWithText("粘贴").performClick()
        compose.runOnIdle { assertEquals("Renamed", title) }
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals("Renamed", saved) }
        compose.onNodeWithTag("rename-session-dialog").assertDoesNotExist()
        compose.onNodeWithTag("dsh-text-menu").assertDoesNotExist()
    }

    @Test fun cutDisablesSavingAndCancelAllowsReopeningTextMenu() {
        var title by mutableStateOf("Greeting")
        var visible by mutableStateOf(true)
        compose.setContent {
            DshTheme {
                Box {
                    Text("打开重命名", Modifier.clickable {
                        title = "Greeting"
                        visible = true
                    })
                    if (visible) {
                        RenameSessionDialog(title, { title = it }, { visible = false }) {}
                    }
                }
            }
        }

        val editor = compose.onNodeWithTag("rename-session-title")
        editor.performTouchInput { longClick(Offset(70f, center.y)) }
        compose.onNodeWithText("剪切").performClick()
        compose.runOnIdle { assertEquals("", title) }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        editor.performTextInput(" ")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("rename-session-dialog").assertDoesNotExist()
        compose.onNodeWithTag("dsh-text-menu").assertDoesNotExist()

        compose.onNodeWithText("打开重命名").performClick()
        editor.performTouchInput { longClick(Offset(70f, center.y)) }
        compose.onNodeWithTag("dsh-text-menu").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("rename-session-dialog").assertDoesNotExist()
        compose.onNodeWithTag("dsh-text-menu").assertDoesNotExist()
    }
}
