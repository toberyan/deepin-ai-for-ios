package org.deepin.uosai.companion.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ArtifactPreviewPage(
    val state: String,
    val revision: String,
    val content: JsonObject?,
    val truncated: Boolean,
    val nextCursor: JsonObject?,
)

data class AccumulatedArtifactPreview(
    val revision: String,
    val content: JsonObject,
    val truncated: Boolean,
    val nextCursor: JsonObject?,
)

data class LoadedArtifactPreview(
    val artifactId: String,
    val previewKind: PreviewKind,
    val state: String,
    val revision: String = "",
    val content: JsonObject? = null,
    val truncated: Boolean = false,
)

sealed interface ArtifactPreviewPageResult {
    data class Continue(val preview: AccumulatedArtifactPreview) : ArtifactPreviewPageResult
    data object Reload : ArtifactPreviewPageResult
}

fun appendArtifactPreviewPage(
    current: AccumulatedArtifactPreview?,
    page: ArtifactPreviewPage,
): ArtifactPreviewPageResult {
    if (page.state != "ready" || page.revision.isBlank() || page.content == null) {
        return ArtifactPreviewPageResult.Reload
    }
    if (current != null && current.revision != page.revision) {
        return ArtifactPreviewPageResult.Reload
    }
    val content = if (current == null) {
        page.content
    } else {
        mergePreviewContent(current.content, page.content)
    }
    return ArtifactPreviewPageResult.Continue(
        AccumulatedArtifactPreview(
            revision = page.revision,
            content = content,
            truncated = page.truncated,
            nextCursor = page.nextCursor,
        ),
    )
}

private fun mergePreviewContent(previous: JsonObject, next: JsonObject): JsonObject {
    val firstText = (previous["text"] as? JsonPrimitive)?.contentOrNull
    val nextText = (next["text"] as? JsonPrimitive)?.contentOrNull
    if (firstText == null || nextText == null) return next
    return JsonObject(previous + ("text" to JsonPrimitive(firstText + nextText)))
}
