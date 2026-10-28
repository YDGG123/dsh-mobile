package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ConversationSidebarDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()
    private var animatorScale: String? = null

    @Before fun pauseBackgroundAnimation() {
        animatorScale = Settings.Global.getString(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver, "animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
    }

    @After fun restoreBackgroundAnimation() {
        shell(animatorScale?.let { "settings put global animator_duration_scale $it" } ?: "settings delete global animator_duration_scale")
    }

    @Test
    fun floatingSidebarSwitchesSessionsClosesOnBackAndKeepsContentWidth() {
        var selected by mutableStateOf("one")
        var destination = ""
        val sessions = listOf("one", "two").map { SessionSummary(it, it, 1.0, false, false) }
        compose.setContent {
            DshTheme {
                DshMenuHost {
                    Box(Modifier.fillMaxSize().testTag("sidebar-page")) {
                        Column(Modifier.padding(24.dp)) {
                            ConversationSidebarButton(
                                listOf(GatewayWorkspace("workspace", "/project", "project", listOf("one", "two"), "", "")),
                                sessions, selected, {}, { selected = it },
                                { destination = "plugins" }, { destination = "tasks" }, { destination = "settings" }
                            )
                        }
                        Text("正文", Modifier.align(Alignment.Center).testTag("sidebar-content"))
                    }
                }
            }
        }
        val original = compose.onNodeWithTag("sidebar-content").fetchSemanticsNode().boundsInRoot
        fun open() { compose.onNodeWithTag("conversation-sidebar-toggle").performClick() }
        open()
        compose.onNodeWithTag("conversation-sidebar").assertIsDisplayed()
        captureSidebar()
        compose.onNodeWithTag("conversation-sidebar-session-one").assertIsSelected()
        assertEquals(original, compose.onNodeWithTag("sidebar-content").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("conversation-sidebar-session-two").performClick()
        compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
        compose.runOnIdle { assertEquals("two", selected) }
        open()
        compose.onNodeWithTag("conversation-sidebar-session-two").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
        for ((tag, expected) in listOf("plugins" to "plugins", "scheduled-tasks" to "tasks", "settings" to "settings")) {
            open()
            compose.onNodeWithTag("conversation-sidebar-$tag").performClick()
            compose.onNodeWithTag("conversation-sidebar").assertDoesNotExist()
            compose.runOnIdle { assertEquals(expected, destination) }
        }
    }

    @Test
    fun tabletEntryIsWiredToAppNavigation() {
        val holder = AndroidSharedStateHolder().apply { loadFixture() }
        val sessionId = holder.snapshot.sessions.first().id
        var sidebarAvailable = false
        try {
            compose.setContent {
                DshTheme {
                    val window = adaptiveWindowSize()
                    sidebarAvailable = AdaptiveLayout.conversationSidebarAvailable(window.width.value, window.height.value)
                    DshProductApp(holder, {}, {}, null, {})
                }
            }
            compose.waitForIdle()
            assumeTrue("此用例需要平板或展开后的窗口", sidebarAvailable)
            compose.onNodeWithTag("workspace-session-$sessionId").performScrollTo().performClick()
            compose.onNodeWithTag("conversation-sidebar-toggle").assertIsDisplayed().performClick()
            compose.onNodeWithTag("conversation-sidebar-plugins").performClick()
            compose.onNodeWithText("插件").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("conversation-sidebar-toggle").assertIsDisplayed().performClick()
            compose.onNodeWithTag("conversation-sidebar-settings").performClick()
            compose.onNodeWithText("设置").assertIsDisplayed()
            compose.runOnIdle { assertEquals(sessionId, holder.snapshot.selectedSessionId) }
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    private fun captureSidebar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        instrumentation.targetContext.cacheDir.resolve("conversation-sidebar-tablet.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
