package org.deepin.uosai.companion.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject

class RemoteFrameTest {
    @Test
    fun startTurnUsesTheV1WireName() {
        val json = RemoteJson.encode(CommandFrame.startTurn(
            requestId = "r1",
            workspaceId = "w1",
            conversationId = "c1",
            message = "continue",
            assistantId = "uos-claw",
            modelId = "deepseek-chat",
        ))

        assertTrue(json.contains("\"command\":\"start_turn\""))
        assertTrue(json.contains("\"major\":1"))
    }

    @Test
    fun unknownEventsRemainDecodable() {
        val frame = RemoteFrame.parseInbound(
            """{"kind":"event","protocol":{"major":1,"minor":0},"conversationId":"c1","sequence":4,"event":"future_event","payload":{}}""",
        )

        assertEquals(RemoteEvent.Unknown("future_event"), (frame as InboundFrame.Event).event.event)
    }

    @Test
    fun artifactPreviewUsesTheAdditiveV1WireName() {
        val json = RemoteJson.encode(
            CommandFrame.getArtifactPreview(
                requestId = "preview-1",
                conversationId = "conversation-1",
                artifactId = "artifact-1",
            ),
        )

        assertTrue(json.contains("\"command\":\"get_artifact_preview\""))
        assertTrue(json.contains("\"artifactId\":\"artifact-1\""))
        assertTrue(json.contains("\"cursor\":{}"))
    }

    @Test
    fun creationCommandsUseTheAdditiveV1WireNames() {
        val options = RemoteJson.encode(CommandFrame.getConversationCreationOptions("options-1"))
        val create = RemoteJson.encode(
            CommandFrame.createConversation(
                requestId = "create-1",
                workspaceId = "workspace-1",
                assistantId = "uos-ai-claw",
                modelId = "model-1",
            ),
        )

        assertTrue(options.contains("\"command\":\"get_conversation_creation_options\""))
        assertTrue(options.contains("\"payload\":{}"))
        assertTrue(create.contains("\"command\":\"create_conversation\""))
        assertTrue(create.contains("\"workspaceId\":\"workspace-1\""))
        assertTrue(create.contains("\"assistantId\":\"uos-ai-claw\""))
        assertTrue(create.contains("\"modelId\":\"model-1\""))
    }

    @Test
    fun workbenchEventNamesRemainTyped() {
        assertEquals(RemoteEvent.AgentRunDelta, RemoteEvent.fromWireName("agent_run_delta"))
        assertEquals(RemoteEvent.AgentActivityDelta, RemoteEvent.fromWireName("agent_activity_delta"))
        assertEquals(RemoteEvent.ArtifactDelta, RemoteEvent.fromWireName("artifact_delta"))
        assertEquals(RemoteEvent.TaskStatusDelta, RemoteEvent.fromWireName("task_status_delta"))
    }

    @Test
    fun contentAndUnknownTaskStatusRemainTolerantlyDecodable() {
        val content = ConversationFrame.parseContent(
            RemoteJson.codec.parseToJsonElement(
                """{"blocks":[{"id":"answer","kind":"markdown","payload":{"text":"Visible answer"}}],"artifacts":[{"id":"artifact-1","name":"report.html","previewKind":"html_document","revision":"r1"}]}""",
            ).jsonObject,
        )

        assertEquals("Visible answer", content.blocks.single().let { (it as org.deepin.uosai.companion.app.RenderBlock.Markdown).text })
        assertEquals("report.html", content.artifacts.getValue("artifact-1").name)
        assertNull(ConversationFrame.taskStatus("future_status"))
    }
}
