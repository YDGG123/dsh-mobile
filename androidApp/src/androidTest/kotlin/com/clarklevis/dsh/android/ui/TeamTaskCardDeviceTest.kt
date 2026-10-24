package com.clarklevis.dsh.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clarklevis.dsh.shared.facade.TeamMemberView
import com.clarklevis.dsh.shared.facade.TeamTaskView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TeamTaskCardDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val task = TeamTaskView(
        id = "task-1", revision = 3, subject = "素材分析", description = "任务说明",
        status = "completed", ownerName = "scout", writeScopes = listOf("analysis")
    )
    private val owner = TeamMemberView(id = "scout-session", name = "scout", avatarIndex = 2)

    @Test
    fun cardAndDisclosureBothToggleDetailsWithoutOpeningConversation() {
        val selected = mutableListOf<String>()
        compose.setContent {
            DshTheme { TeamTaskCard(task, owner, emptyList()) { selected += it.id } }
        }
        compose.onNodeWithText("版本：3").assertDoesNotExist()
        compose.onNodeWithTag("team-task-toggle-task-1").performClick()
        compose.onNodeWithText("版本：3").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
        compose.onNodeWithText("素材分析", useUnmergedTree = true).performClick()
        compose.onNodeWithText("版本：3").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
        compose.onNodeWithText("素材分析", useUnmergedTree = true).performClick()
        compose.onNodeWithText("版本：3").assertIsDisplayed()
        compose.onNodeWithTag("team-task-toggle-task-1").performClick()
        compose.onNodeWithText("版本：3").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
    }

    @Test
    fun onlyOwnerOpensConversationWithoutTogglingDetails() {
        var selections = 0
        compose.setContent {
            DshTheme { TeamTaskCard(task, owner, emptyList()) { selections++ } }
        }
        compose.onNodeWithTag("team-task-owner-task-1").performClick()
        compose.onNodeWithText("版本：3").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, selections) }
        compose.onNodeWithTag("team-task-toggle-task-1").performClick()
        compose.onNodeWithTag("team-task-owner-task-1").performClick()
        compose.onNodeWithText("版本：3").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, selections) }
    }

    @Test
    fun unassignedTaskCanExpandWithoutOpeningAnotherConversation() {
        var selections = 0
        compose.setContent {
            DshTheme { TeamTaskCard(task.copy(ownerName = null), null, emptyList()) { selections++ } }
        }
        compose.onNodeWithTag("team-task-toggle-task-1").performClick()
        compose.onNodeWithText("版本：3").assertIsDisplayed()
        compose.onNodeWithText("素材分析", useUnmergedTree = true).performClick()
        compose.onNodeWithText("版本：3").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, selections) }
    }
}
