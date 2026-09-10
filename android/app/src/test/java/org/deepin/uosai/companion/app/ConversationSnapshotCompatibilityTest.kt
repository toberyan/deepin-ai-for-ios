package org.deepin.uosai.companion.app

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import org.deepin.uosai.companion.core.protocol.RemoteJson
import org.junit.Test

class ConversationSnapshotCompatibilityTest {
    @Test
    fun legacySnapshotWithoutWorkbenchKeepsTranscriptAndUsesEmptyWorkbench() {
        val snapshot = conversationSnapshotProjection(
            RemoteJson.codec.parseToJsonElement(
                """{
                  "sequence": 41,
                  "render": {
                    "messages": {
                      "message-1": {
                        "role": 1,
                        "render_message": [{"data": {"content": "Continue the task"}}]
                      }
                    }
                  }
                }""",
            ).jsonObject,
        )

        assertEquals(41, snapshot.workbench.sequence)
        assertTrue(snapshot.workbench.runs.isEmpty())
        assertEquals(listOf(TranscriptEntry("message-1", TranscriptRole.User, "Continue the task")), snapshot.transcript)
    }
}
