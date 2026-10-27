package com.clarklevis.dsh.android.ui

import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionNavigationDeviceTest {
    @get:Rule val locale = ChineseDeviceLocaleRule()
    @get:Rule val compose = createComposeRule()
    private var animatorScale: String? = null

    @Before fun pauseBackgroundAnimation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        animatorScale = Settings.Global.getString(context.contentResolver, "animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
    }

    @After fun restoreBackgroundAnimation() {
        val scale = animatorScale
        shell(if (scale == null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $scale")
    }

    @Test fun rapidSessionClicksOpenOnePageAndCanReopenAfterReturning() {
        val holder = AndroidSharedStateHolder().apply { loadFixture() }
        val sessionId = holder.snapshot.sessions.first().id
        try {
            compose.setContent {
                DshTheme {
                    DshProductApp(holder, {}, {}, null, {})
                }
            }
            repeat(2) {
                val row = compose.onNodeWithTag("workspace-session-$sessionId").performScrollTo()
                val open = checkNotNull(row.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
                // 同一帧内连续触发，模拟第二次点击早于转场和重组完成。
                compose.runOnIdle {
                    open()
                    open()
                }
                compose.onNodeWithContentDescription("返回").assertIsDisplayed()
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithTag("workspace-screen").assertIsDisplayed()
                compose.onNodeWithContentDescription("返回").assertDoesNotExist()
            }
        } finally {
            compose.runOnIdle { holder.close() }
        }
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
