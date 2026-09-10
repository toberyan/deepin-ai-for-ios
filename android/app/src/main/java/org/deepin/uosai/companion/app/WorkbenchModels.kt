package org.deepin.uosai.companion.app

enum class AgentRunState { Running, WaitingApproval, Completed, Failed, Unknown }
enum class ActivityKind { Status, ToolRequest, Notice, Unknown }
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

data class WorkbenchState(
    val sequence: Long = 0,
    val capabilities: Set<WorkbenchCapability> = emptySet(),
    val activeRunId: String? = null,
    val runs: Map<String, AgentRun> = emptyMap(),
    val activities: Map<String, AgentActivity> = emptyMap(),
    val artifacts: Map<String, ArtifactRef> = emptyMap(),
    val blocks: List<RenderBlock> = emptyList(),
)
