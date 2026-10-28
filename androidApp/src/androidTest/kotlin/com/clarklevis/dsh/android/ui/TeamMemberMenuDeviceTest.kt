package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.TeamMemberView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TeamMemberMenuDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectionMovesBadgeAndCheckTogetherWithoutChangingRowHeight() {
        compose.setContent {
            var selected by mutableIntStateOf(0)
            DshTheme {
                Column {
                    listOf(
                        TeamMemberView("lead", "lead"),
                        TeamMemberView("scout", "非常长的成员名称，需要自动省略", model = "deepseek-flash", detail = "很长的任务描述，仍然只能占用固定的一行内容"),
                    ).forEachIndexed { index, member ->
                        TeamMemberItem(member, selected == index) { selected = index }
                    }
                }
            }
        }
        compose.onNodeWithTag("team-member-lead").assertIsSelected()
        compose.onNodeWithTag("team-member-selected-lead", useUnmergedTree = true).assertIsDisplayed()
        val leadHeight =
            compose
                .onNodeWithTag("team-member-lead")
                .fetchSemanticsNode()
                .boundsInRoot.height
        val scoutHeight =
            compose
                .onNodeWithTag("team-member-scout")
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertEquals(leadHeight, scoutHeight)
        // 英文与中文回退字体的实际字面高度不同；两行都应完整显示且不超出成员行。
        val metadataHeight =
            compose
                .onNodeWithText("deepseek-flash · ${appText("状态同步中")}", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.height
        val detailHeight =
            compose
                .onNodeWithText("很长的任务描述，仍然只能占用固定的一行内容", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.height
        val minimumLineHeight = with(compose.density) { 16.sp.toPx() }
        assertTrue(metadataHeight >= minimumLineHeight)
        assertTrue(detailHeight >= minimumLineHeight)
        val detailBounds = compose
            .onNodeWithText("很长的任务描述，仍然只能占用固定的一行内容", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val memberBounds = compose.onNodeWithTag("team-member-scout").fetchSemanticsNode().boundsInRoot
        assertTrue(detailBounds.bottom <= memberBounds.bottom)
        compose.onNodeWithTag("team-member-scout").performClick()
        compose.onNodeWithTag("team-member-scout").assertIsSelected()
        compose.onNodeWithTag("team-member-selected-scout", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("team-member-selected-lead", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(appText("当前会话"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun taskFilterExposesOneSelectedSegmentAndSwitchesOnTap() {
        compose.setContent {
            var selected by mutableIntStateOf(0)
            DshTheme { TeamTaskFilter(selected) { selected = it } }
        }
        compose.onNodeWithText(appText("全部")).assertIsSelected()
        compose.onNodeWithText(appText("当前成员")).performClick().assertIsSelected()
        compose.onNodeWithText(appText("未分配")).performClick().assertIsSelected()
    }
}
