package org.deepin.uosai.companion.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun workbenchEventNamesRemainTyped() {
        assertEquals(RemoteEvent.AgentRunDelta, RemoteEvent.fromWireName("agent_run_delta"))
        assertEquals(RemoteEvent.AgentActivityDelta, RemoteEvent.fromWireName("agent_activity_delta"))
        assertEquals(RemoteEvent.ArtifactDelta, RemoteEvent.fromWireName("artifact_delta"))
    }
}
