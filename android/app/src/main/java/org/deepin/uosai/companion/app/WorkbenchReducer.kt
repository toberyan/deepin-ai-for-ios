package org.deepin.uosai.companion.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.deepin.uosai.companion.core.protocol.RemoteEvent
import org.deepin.uosai.companion.core.protocol.RemoteEventFrame
import org.deepin.uosai.companion.core.protocol.WorkbenchFrame

sealed interface WorkbenchReduceResult {
    data class Applied(val state: WorkbenchState) : WorkbenchReduceResult
    data class Ignored(val state: WorkbenchState) : WorkbenchReduceResult
    data object Reload : WorkbenchReduceResult
}

fun reduceWorkbench(state: WorkbenchState, frame: RemoteEventFrame): WorkbenchReduceResult {
    if (frame.sequence <= state.sequence) return WorkbenchReduceResult.Ignored(state)
    if (frame.sequence != state.sequence + 1) return WorkbenchReduceResult.Reload

    val next = when (frame.event) {
        RemoteEvent.AgentRunDelta -> state.copy(runs = applyRuns(state.runs, frame.payload))
        RemoteEvent.AgentActivityDelta -> state.copy(activities = applyActivities(state.activities, frame.payload))
        RemoteEvent.ArtifactDelta -> state.copy(artifacts = applyArtifacts(state.artifacts, frame.payload))
        else -> state
    }
    return WorkbenchReduceResult.Applied(next.copy(sequence = frame.sequence))
}

private fun applyRuns(current: Map<String, AgentRun>, payload: JsonObject): Map<String, AgentRun> {
    val next = current.toMutableMap()
    payload.removeIds().forEach(next::remove)
    payload.upserts().forEach { value ->
        val id = value.stringValue("id")?.takeIf(String::isNotBlank) ?: return@forEach
        val previous = next[id]
        next[id] = AgentRun(
            id = id,
            parentId = if (value.containsKey("parentId")) value.stringValue("parentId") else previous?.parentId,
            title = value.stringValue("title") ?: previous?.title ?: "Agent",
            state = value.stringValue("state")?.let(WorkbenchFrame::runState) ?: previous?.state ?: AgentRunState.Unknown,
        )
    }
    return next
}

private fun applyActivities(current: Map<String, AgentActivity>, payload: JsonObject): Map<String, AgentActivity> {
    val next = current.toMutableMap()
    payload.removeIds().forEach(next::remove)
    payload.upserts().forEach { value ->
        val id = value.stringValue("id")?.takeIf(String::isNotBlank) ?: return@forEach
        val previous = next[id]
        next[id] = AgentActivity(
            id = id,
            runId = value.stringValue("runId") ?: previous?.runId.orEmpty(),
            kind = value.stringValue("kind")?.let(WorkbenchFrame::activityKind) ?: previous?.kind ?: ActivityKind.Unknown,
            state = value.stringValue("state")?.let(WorkbenchFrame::runState) ?: previous?.state ?: AgentRunState.Unknown,
            title = value.stringValue("title") ?: previous?.title ?: "Agent activity",
            detail = value.stringValue("detail") ?: previous?.detail,
        )
    }
    return next
}

private fun applyArtifacts(current: Map<String, ArtifactRef>, payload: JsonObject): Map<String, ArtifactRef> {
    val next = current.toMutableMap()
    payload.removeIds().forEach(next::remove)
    payload.upserts().forEach { value ->
        val parsed = WorkbenchFrame.parseArtifact(value) ?: return@forEach
        val previous = next[parsed.id]
        next[parsed.id] = parsed.copy(
            runId = if (value.containsKey("runId")) parsed.runId else previous?.runId,
            revision = parsed.revision.ifBlank { previous?.revision.orEmpty() },
        )
    }
    return next
}

private fun JsonObject.upserts(): List<JsonObject> =
    (get("upsert") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.removeIds(): List<String> =
    (get("removeIds") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.stringValue(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull
