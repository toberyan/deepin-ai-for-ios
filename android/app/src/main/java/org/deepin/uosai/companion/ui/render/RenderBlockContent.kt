package org.deepin.uosai.companion.ui.render

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontFamily
import org.deepin.uosai.companion.app.RenderBlock

private const val MAX_INLINE_IMAGE_BYTES = 10 * 1024 * 1024

@Composable
fun RenderBlockContent(
    block: RenderBlock,
    onOpenArtifact: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (block) {
        is RenderBlock.Markdown -> SelectableBlock(block.text, modifier)
        is RenderBlock.Code -> Card(modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    text = block.text,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        is RenderBlock.Image -> InlineImage(block, modifier)
        is RenderBlock.FileReference -> Card(modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(block.label, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { onOpenArtifact(block.artifactId) }) {
                    Text("Open artifact ${block.label}")
                }
            }
        }
        is RenderBlock.Unsupported -> UnsupportedBlock(block.label, modifier)
    }
}

@Composable
private fun SelectableBlock(text: String, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text = text, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun InlineImage(block: RenderBlock.Image, modifier: Modifier) {
    val bitmap = runCatching {
        val bytes = Base64.decode(block.base64, Base64.DEFAULT)
        if (bytes.size > MAX_INLINE_IMAGE_BYTES) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
    if (bitmap == null) {
        UnsupportedBlock("Image preview unavailable", modifier)
        return
    }
    Card(modifier.fillMaxWidth()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Rendered image",
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun UnsupportedBlock(label: String, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Text("Unsupported content: $label", modifier = Modifier.padding(12.dp))
    }
}
