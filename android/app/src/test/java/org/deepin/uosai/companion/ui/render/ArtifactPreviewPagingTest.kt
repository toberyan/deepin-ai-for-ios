package org.deepin.uosai.companion.ui.render

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.deepin.uosai.companion.app.ArtifactPreviewPage
import org.deepin.uosai.companion.app.ArtifactPreviewPageResult
import org.deepin.uosai.companion.app.appendArtifactPreviewPage
import org.junit.Test

class ArtifactPreviewPagingTest {
    @Test
    fun joinsPagesOnlyWhenTheRevisionMatches() {
        val first = appendArtifactPreviewPage(
            current = null,
            page = ArtifactPreviewPage(
                state = "ready",
                revision = "r1",
                content = buildJsonObject { put("text", "<h1>Part one</h1>") },
                truncated = true,
                nextCursor = buildJsonObject { put("offset", 32) },
            ),
        )
        val accumulated = assertIs<ArtifactPreviewPageResult.Continue>(first).preview

        val second = appendArtifactPreviewPage(
            current = accumulated,
            page = ArtifactPreviewPage(
                state = "ready",
                revision = "r1",
                content = buildJsonObject { put("text", "<p>Part two</p>") },
                truncated = false,
                nextCursor = null,
            ),
        )
        val completed = assertIs<ArtifactPreviewPageResult.Continue>(second).preview
        assertEquals("<h1>Part one</h1><p>Part two</p>", completed.content.getValue("text").toString().trim('"'))
        assertEquals(false, completed.truncated)

        val changed = appendArtifactPreviewPage(
            current = completed,
            page = ArtifactPreviewPage("ready", "r2", buildJsonObject { put("text", "new") }, false, null),
        )
        assertEquals(ArtifactPreviewPageResult.Reload, changed)
    }

    @Test
    fun stalePreviewAlwaysRequestsAReloadInsteadOfShowingMixedContent() {
        val result = appendArtifactPreviewPage(
            current = null,
            page = ArtifactPreviewPage("stale_preview", "r1", null, false, null),
        )

        assertEquals(ArtifactPreviewPageResult.Reload, result)
    }
}
