package org.deepin.uosai.companion.core.protocol

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import org.deepin.uosai.companion.app.AgentRunState
import org.deepin.uosai.companion.app.PreviewKind
import org.deepin.uosai.companion.app.RenderBlock
import org.junit.Test

class WorkbenchFrameTest {
    @Test
    fun decodesKnownWorkbenchEntriesAndKeepsUnknownBlocksSafe() {
        val snapshot = WorkbenchFrame.parseSnapshot(
            RemoteJson.codec.parseToJsonElement(
                """{
                  "schemaVersion":1,
                  "capabilities":["continue","cancel","approve","artifact_preview"],
                  "activeRunId":"run-1",
                  "runs":[
                    {"id":"run-1","parentId":null,"title":"UOS AI Agent","state":"running"},
                    {"id":"child-1","parentId":"run-1","title":"Inspect sources","state":"completed"}
                  ],
                  "activities":[{"id":"tool-1","runId":"run-1","kind":"tool_request","state":"completed","title":"shell"}],
                  "artifacts":[{"id":"artifact-1","runId":"child-1","name":"report.html","previewKind":"html_document","revision":"r1"}],
                  "renderBlocks":[
                    {"id":"markdown-1","kind":"markdown","payload":{"text":"Visible output"}},
                    {"id":"future-1","kind":"3d_scene","payload":{"raw":"must not surface"}}
                  ]
                }""",
            ) as JsonObject,
            sequence = 10,
        )

        assertNotNull(snapshot)
        assertEquals(10, snapshot.sequence)
        assertEquals(AgentRunState.Running, snapshot.runs.getValue("run-1").state)
        assertEquals("run-1", snapshot.runs.getValue("child-1").parentId)
        assertEquals(PreviewKind.HtmlDocument, snapshot.artifacts.getValue("artifact-1").previewKind)
        assertIs<RenderBlock.Markdown>(snapshot.blocks.first())
        val unsupported = snapshot.blocks.last()
        assertIs<RenderBlock.Unsupported>(unsupported)
        assertEquals("3d_scene", unsupported.label)
    }

    @Test
    fun rejectsUnsupportedSnapshotVersions() {
        val snapshot = WorkbenchFrame.parseSnapshot(
            RemoteJson.codec.parseToJsonElement("""{"schemaVersion":2}""") as JsonObject,
            sequence = 4,
        )

        assertNull(snapshot)
    }
}
