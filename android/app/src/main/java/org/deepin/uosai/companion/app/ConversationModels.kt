package org.deepin.uosai.companion.app

enum class TaskStatus { Running, AwaitingApproval, Completed, Failed }

data class CompanionWorkspace(
    val id: String,
    val label: String,
    val conversations: List<CompanionConversation>,
)

data class CompanionConversation(
    val id: String,
    val title: String,
    val workspaceId: String = "",
    val updatedAt: Long = 0,
    val assistantId: String = "",
    val modelId: String = "",
    val taskStatus: TaskStatus? = null,
    val sequence: Long = 0,
)

data class CreationWorkspace(val id: String, val label: String)
data class CreationModel(val id: String, val name: String)
data class CreationAgent(val id: String, val name: String, val models: List<CreationModel>)
data class ConversationCreationOptions(
    val workspaces: List<CreationWorkspace> = emptyList(),
    val agents: List<CreationAgent> = emptyList(),
)

enum class PreviewKind { Text, Image, StaticMarkup, HtmlDocument, FileReference, Unknown }

data class ArtifactRef(
    val id: String,
    val runId: String? = null,
    val name: String,
    val previewKind: PreviewKind,
    val revision: String,
)

sealed interface RenderBlock {
    val id: String

    data class Markdown(override val id: String, val text: String) : RenderBlock
    data class Code(override val id: String, val language: String, val text: String) : RenderBlock
    data class Image(override val id: String, val base64: String, val mimeType: String) : RenderBlock
    data class FileReference(override val id: String, val artifactId: String, val label: String) : RenderBlock
    data class Unsupported(override val id: String, val label: String) : RenderBlock
}

data class ConversationContent(
    val blocks: List<RenderBlock> = emptyList(),
    val artifacts: Map<String, ArtifactRef> = emptyMap(),
)
