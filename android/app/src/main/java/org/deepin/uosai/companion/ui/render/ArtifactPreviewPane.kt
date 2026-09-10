package org.deepin.uosai.companion.ui.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.deepin.uosai.companion.app.ArtifactRef
import org.deepin.uosai.companion.app.LoadedArtifactPreview
import org.deepin.uosai.companion.app.PreviewKind
import org.deepin.uosai.companion.app.RenderBlock

@Composable
fun ArtifactPreviewPane(
    artifact: ArtifactRef?,
    preview: LoadedArtifactPreview?,
    onLoad: (ArtifactRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artifact == null) {
        Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Select an artifact to preview it here.")
        }
        return
    }
    val currentPreview = preview?.takeIf { it.artifactId == artifact.id }
    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(artifact.name, style = MaterialTheme.typography.titleMedium)
        when (currentPreview?.state) {
            null -> Button(onClick = { onLoad(artifact) }) { Text("Open artifact ${artifact.name}") }
            "loading" -> Text("Loading preview…")
            "ready" -> PreviewContent(artifact, currentPreview.content ?: JsonObject(emptyMap()), Modifier.fillMaxSize())
            "stale_preview" -> PreviewNotice("This artifact changed. Reload it to avoid mixed content.", artifact, onLoad)
            "too_large" -> PreviewNotice("This artifact is too large to preview on this device.", artifact, onLoad)
            "unsupported" -> PreviewNotice("This artifact format is not supported for preview.", artifact, onLoad)
            "inaccessible" -> PreviewNotice("This artifact is no longer accessible.", artifact, onLoad)
            else -> PreviewNotice("Preview could not be loaded.", artifact, onLoad)
        }
    }
}

@Composable
private fun PreviewNotice(message: String, artifact: ArtifactRef, onLoad: (ArtifactRef) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message)
            Button(onClick = { onLoad(artifact) }) { Text("Reload artifact") }
        }
    }
}

@Composable
private fun PreviewContent(artifact: ArtifactRef, content: JsonObject, modifier: Modifier) {
    val text = (content["text"] as? JsonPrimitive)?.contentOrNull
    when (artifact.previewKind) {
        PreviewKind.HtmlDocument, PreviewKind.StaticMarkup -> {
            if (text == null) {
                PreviewText("Preview content is unavailable.", modifier)
            } else {
                SafeHtmlPreview(text, modifier)
            }
        }
        PreviewKind.Image -> {
            val base64 = (content["image_base64"] as? JsonPrimitive)?.contentOrNull
            val mimeType = (content["mime_type"] as? JsonPrimitive)?.contentOrNull ?: "image/png"
            if (base64 == null) PreviewText("Preview content is unavailable.", modifier)
            else RenderBlockContent(RenderBlock.Image(artifact.id, base64, mimeType), modifier = modifier)
        }
        else -> PreviewText(text ?: "Preview content is unavailable.", modifier)
    }
}

@Composable
private fun PreviewText(text: String, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text, modifier = Modifier.padding(12.dp))
        }
    }
}
