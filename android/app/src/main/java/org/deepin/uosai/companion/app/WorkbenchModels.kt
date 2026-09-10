package org.deepin.uosai.companion.app

enum class AgentRunState { Running, WaitingApproval, Completed, Failed, Unknown }
enum class ActivityKind { Status, ToolRequest, Notice, Unknown }
enum class PreviewKind { Text, Image, StaticMarkup, HtmlDocument, FileReference, Unknown }
enum class WorkbenchCapability { Continue, Cancel, Approve, ArtifactPreview }

data class AgentRun(
    val id: String,
    val parentId: String?,
    val title: String,
    val state: AgentRunState,
)

data class AgentActivity(
    val id: String,
    val runId: String,
    val kind: ActivityKind,
    val state: AgentRunState,
    val title: String,
    val detail: String? = null,
)

data class ArtifactRef(
    val id: String,
    val runId: String?,
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

data class WorkbenchState(
    val sequence: Long = 0,
    val capabilities: Set<WorkbenchCapability> = emptySet(),
    val activeRunId: String? = null,
    val runs: Map<String, AgentRun> = emptyMap(),
    val activities: Map<String, AgentActivity> = emptyMap(),
    val artifacts: Map<String, ArtifactRef> = emptyMap(),
    val blocks: List<RenderBlock> = emptyList(),
)
