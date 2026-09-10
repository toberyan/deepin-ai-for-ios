package org.deepin.uosai.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.deepin.uosai.companion.app.AgentRun
import org.deepin.uosai.companion.app.AgentRunState
import org.deepin.uosai.companion.app.ArtifactRef
import org.deepin.uosai.companion.app.CompanionConversation
import org.deepin.uosai.companion.app.CompanionUiState
import org.deepin.uosai.companion.app.CompanionWorkspace
import org.deepin.uosai.companion.app.PreviewKind
import org.deepin.uosai.companion.app.WorkbenchState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkbenchContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    fun tabletShowsWorkspaceConversationAndAgentWorkbenchTogether() {
        val conversation = CompanionConversation("conversation-1", "Planning")
        var selectedRun: String? = null
        composeRule.setContent {
            MaterialTheme {
                CompanionContent(
                    state = CompanionUiState(
                        pairedHostName = "UOS AI",
                        workspaces = listOf(CompanionWorkspace("workspace-1", "Product", listOf(conversation))),
                        selectedWorkspaceId = "workspace-1",
                        selectedConversation = conversation,
                        activeTurn = true,
                        workbench = WorkbenchState(
                            activeRunId = "run-1",
                            runs = mapOf(
                                "run-1" to AgentRun("run-1", null, "UOS AI Agent", AgentRunState.Running),
                                "child-1" to AgentRun("child-1", "run-1", "Inspect sources", AgentRunState.Running),
                            ),
                            artifacts = mapOf(
                                "artifact-1" to ArtifactRef("artifact-1", "child-1", "report.html", PreviewKind.HtmlDocument, "r1"),
                            ),
                        ),
                    ),
                    actions = CompanionActions(
                        reconnect = {},
                        pair = {},
                        dismissError = {},
                        selectWorkspace = {},
                        openConversation = {},
                        closeConversation = {},
                        startTurn = { _, _, _ -> },
                        cancelTurn = {},
                        answerApproval = {},
                        selectAgentRun = { selectedRun = it },
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Shared workspaces").assertIsDisplayed()
        composeRule.onNodeWithText("Inspect sources").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Cancel Agent").assertIsDisplayed()
        composeRule.onNodeWithText("Activity").assertIsDisplayed()
        composeRule.onNodeWithText("Artifacts").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("child-1", selectedRun) }
    }
}
