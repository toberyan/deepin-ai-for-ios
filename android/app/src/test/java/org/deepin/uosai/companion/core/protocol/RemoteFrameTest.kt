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
}
