package org.deepin.uosai.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.deepin.uosai.companion.app.CompanionConversation
import org.deepin.uosai.companion.app.CompanionUiState
import org.deepin.uosai.companion.app.ConversationCreationOptions
import org.deepin.uosai.companion.app.CreationAgent
import org.deepin.uosai.companion.app.CreationModel
import org.deepin.uosai.companion.app.CreationWorkspace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    fun recentConversationsOpenTheThreeChoiceCreationFlowWithoutAWorkbench() {
        val conversation = CompanionConversation("conversation-1", "Planning")
        val options = ConversationCreationOptions(
            workspaces = listOf(CreationWorkspace("workspace-1", "Product")),
            agents = listOf(CreationAgent("agent-1", "UOS AI", listOf(CreationModel("model-1", "Model 1")))),
        )

        composeRule.setContent {
            var showCreation by mutableStateOf(false)
            MaterialTheme {
                CompanionContent(
                    state = CompanionUiState(
                        pairedHostName = "UOS AI",
                        conversations = listOf(conversation),
                        creationOptions = if (showCreation) options else null,
                    ),
                    actions = CompanionActions(
                        reconnect = {},
                        pair = {},
                        dismissError = {},
                        selectWorkspace = {},
                        openConversation = {},
                        closeConversation = {},
                        openNewConversation = { showCreation = true },
                        closeNewConversation = { showCreation = false },
                        createConversation = { _, _, _ -> },
                        startTurn = {},
                        cancelTurn = {},
                        answerApproval = {},
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Recent conversations").assertIsDisplayed()
        composeRule.onNodeWithText("New conversation").performClick()
        composeRule.onNodeWithText("Choose workspace").assertIsDisplayed()
        composeRule.onNodeWithText("Choose Agent").assertIsDisplayed()
        composeRule.onNodeWithText("Choose model").assertIsDisplayed()
        composeRule.onAllNodesWithText("Agent workbench").assertCountEquals(0)
        composeRule.onAllNodesWithText("Activity").assertCountEquals(0)
    }
}
