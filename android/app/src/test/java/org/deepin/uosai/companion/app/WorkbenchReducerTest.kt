package org.deepin.uosai.companion.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.deepin.uosai.companion.core.protocol.RemoteEvent
import org.deepin.uosai.companion.core.protocol.RemoteEventFrame
import org.junit.Test

class WorkbenchReducerTest {
    @Test
    fun appliesOnlyTheNextActivitySequence() {
        val state = WorkbenchState(sequence = 10)
        val event = RemoteEventFrame(
            conversationId = "conversation-1",
            sequence = 11,
            event = RemoteEvent.AgentActivityDelta,
            payload = buildJsonObject {
                put("upsert", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "tool-1")
                        put("runId", "run-1")
                        put("kind", "tool_request")
                        put("state", "completed")
                        put("title", "shell")
                    })
                })
                put("removeIds", buildJsonArray { })
            },
        )

        val applied = assertIs<WorkbenchReduceResult.Applied>(reduceWorkbench(state, event))
        assertEquals(11, applied.state.sequence)
        assertEquals("shell", applied.state.activities.getValue("tool-1").title)

        val duplicate = assertIs<WorkbenchReduceResult.Ignored>(reduceWorkbench(applied.state, event))
        assertEquals(applied.state, duplicate.state)
    }

    @Test
    fun gapRequestsASnapshotReload() {
        val state = WorkbenchState(sequence = 10)
        val event = RemoteEventFrame(
            conversationId = "conversation-1",
            sequence = 12,
            event = RemoteEvent.AgentRunDelta,
            payload = buildJsonObject { },
        )

        assertEquals(WorkbenchReduceResult.Reload, reduceWorkbench(state, event))
    }

    @Test
    fun absentPauseCapabilityDoesNotCreateAPauseControl() {
        val state = WorkbenchState(capabilities = setOf(WorkbenchCapability.Continue, WorkbenchCapability.Cancel))

        assertEquals(setOf(WorkbenchCapability.Continue, WorkbenchCapability.Cancel), state.capabilities)
    }
}
