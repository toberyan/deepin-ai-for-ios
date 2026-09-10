package org.deepin.uosai.companion.core.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.deepin.uosai.companion.app.ActivityKind
import org.deepin.uosai.companion.app.AgentActivity
import org.deepin.uosai.companion.app.AgentRun
import org.deepin.uosai.companion.app.AgentRunState
import org.deepin.uosai.companion.app.ArtifactRef
import org.deepin.uosai.companion.app.PreviewKind
import org.deepin.uosai.companion.app.RenderBlock
import org.deepin.uosai.companion.app.WorkbenchCapability
import org.deepin.uosai.companion.app.WorkbenchState

/** Parses only the explicitly versioned, optional workbench extension of protocol v1. */
object WorkbenchFrame {
    fun parseSnapshot(value: JsonObject?, sequence: Long): WorkbenchState? {
        if ((value?.get("schemaVersion") as? JsonPrimitive)?.contentOrNull != "1") return null

        val runs = value.objects("runs").mapNotNull(::parseRun).associateBy(AgentRun::id)
        val activities = value.objects("activities").mapNotNull(::parseActivity).associateBy(AgentActivity::id)
        val artifacts = value.objects("artifacts").mapNotNull(::parseArtifact).associateBy(ArtifactRef::id)
        return WorkbenchState(
            sequence = sequence,
            capabilities = value.stringArray("capabilities").mapNotNull(::capability).toSet(),
            activeRunId = value.stringValue("activeRunId")?.takeIf(String::isNotBlank),
            runs = runs,
            activities = activities,
            artifacts = artifacts,
            blocks = value.objects("renderBlocks").mapNotNull(::parseRenderBlock),
        )
    }

    internal fun parseRun(value: JsonObject): AgentRun? {
        val id = value.stringValue("id")?.takeIf(String::isNotBlank) ?: return null
        return AgentRun(
            id = id,
            parentId = value.stringValue("parentId")?.takeIf(String::isNotBlank),
            title = value.stringValue("title") ?: "Agent",
            state = runState(value.stringValue("state")),
        )
    }

    internal fun parseActivity(value: JsonObject): AgentActivity? {
        val id = value.stringValue("id")?.takeIf(String::isNotBlank) ?: return null
        return AgentActivity(
            id = id,
            runId = value.stringValue("runId") ?: "",
            kind = activityKind(value.stringValue("kind")),
            state = runState(value.stringValue("state")),
            title = value.stringValue("title") ?: "Agent activity",
            detail = value.stringValue("detail")?.takeIf(String::isNotBlank),
        )
    }

    internal fun parseArtifact(value: JsonObject): ArtifactRef? {
        val id = value.stringValue("id")?.takeIf(String::isNotBlank) ?: return null
        val name = value.stringValue("name")?.takeIf(String::isNotBlank) ?: return null
        return ArtifactRef(
            id = id,
            runId = value.stringValue("runId")?.takeIf(String::isNotBlank),
            name = name,
            previewKind = previewKind(value.stringValue("previewKind")),
            revision = value.stringValue("revision") ?: "",
        )
    }

    private fun parseRenderBlock(value: JsonObject): RenderBlock? {
        val id = value.stringValue("id")?.takeIf(String::isNotBlank) ?: return null
        val kind = value.stringValue("kind")?.takeIf(String::isNotBlank) ?: return null
        val payload = value["payload"] as? JsonObject ?: return null
        return when (kind) {
            "markdown" -> payload.stringValue("text")?.let { RenderBlock.Markdown(id, it) }
            "code" -> payload.stringValue("text")?.let { RenderBlock.Code(id, payload.stringValue("language") ?: "", it) }
            "image" -> {
                val base64 = payload.stringValue("base64") ?: return null
                val mimeType = payload.stringValue("mimeType") ?: return null
                RenderBlock.Image(id, base64, mimeType)
            }
            "file_reference" -> {
                val artifactId = payload.stringValue("artifactId") ?: return null
                RenderBlock.FileReference(id, artifactId, payload.stringValue("label") ?: "Artifact")
            }
            else -> RenderBlock.Unsupported(id, kind)
        }
    }

    internal fun runState(value: String?): AgentRunState = when (value) {
        "running" -> AgentRunState.Running
        "waiting_approval" -> AgentRunState.WaitingApproval
        "completed" -> AgentRunState.Completed
        "failed" -> AgentRunState.Failed
        else -> AgentRunState.Unknown
    }

    internal fun activityKind(value: String?): ActivityKind = when (value) {
        "status" -> ActivityKind.Status
        "tool_request" -> ActivityKind.ToolRequest
        "notice" -> ActivityKind.Notice
        else -> ActivityKind.Unknown
    }

    private fun previewKind(value: String?): PreviewKind = when (value) {
        "text" -> PreviewKind.Text
        "image" -> PreviewKind.Image
        "static_markup" -> PreviewKind.StaticMarkup
        "html_document" -> PreviewKind.HtmlDocument
        "file_reference" -> PreviewKind.FileReference
        else -> PreviewKind.Unknown
    }

    private fun capability(value: String): WorkbenchCapability? = when (value) {
        "continue" -> WorkbenchCapability.Continue
        "cancel" -> WorkbenchCapability.Cancel
        "approve" -> WorkbenchCapability.Approve
        "artifact_preview" -> WorkbenchCapability.ArtifactPreview
        else -> null
    }
}

internal fun JsonObject.stringValue(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.objects(name: String): List<JsonObject> =
    (get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

internal fun JsonObject.stringArray(name: String): List<String> =
    (get(name) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
