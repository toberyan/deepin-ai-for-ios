package org.deepin.uosai.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.deepin.uosai.companion.app.CompanionConversation
import org.deepin.uosai.companion.app.CompanionUiState
import org.deepin.uosai.companion.app.CompanionWorkspace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    fun tabletDrawerStartsOpenAndClosesAfterOpeningAConversation() {
        val conversation = CompanionConversation("conversation-1", "Planning")
        var openedConversation: CompanionConversation? = null

        composeRule.setContent {
            MaterialTheme {
                CompanionContent(
                    state = CompanionUiState(
                        pairedHostName = "UOS AI",
                        workspaces = listOf(CompanionWorkspace("workspace-1", "Product", listOf(conversation))),
                        selectedWorkspaceId = "workspace-1",
                    ),
                    actions = CompanionActions(
                        reconnect = {},
                        pair = {},
                        dismissError = {},
                        selectWorkspace = {},
                        openConversation = { openedConversation = it },
                        closeConversation = {},
                        startTurn = { _, _, _ -> },
                        cancelTurn = {},
                        answerApproval = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Shared workspaces").assertIsDisplayed()
        composeRule.onNodeWithText("Planning").performClick()

        composeRule.runOnIdle { assertEquals(conversation, openedConversation) }
        composeRule.onNodeWithText("Shared workspaces").assertIsNotDisplayed()
    }
}
