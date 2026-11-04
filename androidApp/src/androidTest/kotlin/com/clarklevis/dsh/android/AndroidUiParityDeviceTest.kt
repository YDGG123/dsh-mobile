package com.clarklevis.dsh.android

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidUiParityDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("DEPRECATION")
    fun activityUsesResizeInsteadOfPanningTheConversationAboveTheIme() {
        val adjustMode = compose.activity.window.attributes.softInputMode and
            WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST

        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, adjustMode)
    }

    @Test
    fun workspaceHeaderKeepsActionsSquareAndSeparate() {
        // vc62：抽屉已删，字标不再可点、无 contentDescription——本用例只验操作按钮的方形/分离约束。
        val brand = compose.onNode(hasContentDescription("打开侧边栏")).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val authentication = compose.onNode(hasContentDescription("设备认证", substring = true))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val settings = compose.onNode(hasContentDescription("设置")).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val screen = compose.onNode(hasTestTag("workspace-screen"))
            .fetchSemanticsNode().boundsInRoot
        val minimumTouchSize = 48f * compose.activity.resources.displayMetrics.density
        for (bounds in listOf(authentication, settings)) {
            assertTrue("操作按钮必须保持至少 48dp 的点击宽度", bounds.width >= minimumTouchSize)
            assertTrue("操作按钮必须保持至少 48dp 的点击高度", bounds.height >= minimumTouchSize)
            assertEquals("圆形按钮不能被压成椭圆", bounds.width, bounds.height, 1f)
        }
        assertTrue("品牌和认证按钮不得重叠", brand.right <= authentication.left)
        assertTrue("两个操作按钮不得重叠", authentication.right <= settings.left)
        assertTrue("设置按钮不得超出屏幕", settings.right <= screen.right)
        compose.onNode(hasContentDescription("设备认证", substring = true)).performClick()
        compose.onNode(hasTestTag("gateway-auth-menu")).assertIsDisplayed()
    }

    @Test
    fun workspaceChromeMatchesTheIosSourceAndExposesAccessibilitySemantics() {
        compose.onNode(hasTestTag("workspace-screen")).assertIsDisplayed()
        compose.onNode(hasTestTag("workspace-hero-title") and hasText("探索未至之境")).assertIsDisplayed()
        compose.onNode(hasText("DeepSeek Harness 预览版")).assertIsDisplayed()
        compose.onNode(hasText("新建会话")).assertIsDisplayed()
        compose.onNode(hasTestTag("workspace-session-search")).assertIsDisplayed()
        compose.onNode(hasTestTag("workspace-card")).performClick()
        compose.onNode(hasTestTag("workspace-menu")).assertIsDisplayed()
        compose.onNode(hasText("添加工作区")).assertIsDisplayed()
        compose.onNode(hasText("全部会话")).assertDoesNotExist()
        compose.onNode(hasTestTag("workspace-ungrouped")).performClick()
        compose.onNode(hasText("归档")).assertDoesNotExist()
        compose.onNode(hasContentDescription("设备认证", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("设置")).assertIsDisplayed().performClick()
        compose.onNode(hasText("新会话默认配置", substring = true)).assertIsDisplayed()
    }

    @Test
    fun offlineNewSessionOpensComposerWithoutShowingAnInternalSubscribeError() {
        compose.onNode(hasText("新建会话")).performClick()
        compose.onNode(hasTestTag("composer-input")).assertIsDisplayed()
        compose.onNode(hasText("unsubscribe: not-connected")).assertDoesNotExist()
    }
}
