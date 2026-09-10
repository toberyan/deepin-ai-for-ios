package org.deepin.uosai.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.deepin.uosai.companion.app.AgentRun
import org.deepin.uosai.companion.app.AgentRunState
import org.deepin.uosai.companion.app.ArtifactRef
import org.deepin.uosai.companion.app.LoadedArtifactPreview
import org.deepin.uosai.companion.app.WorkbenchState
import org.deepin.uosai.companion.ui.render.ArtifactPreviewPane

data class WorkbenchActions(
    val selectAgentRun: (String?) -> Unit = {},
    val selectArtifact: (ArtifactRef?) -> Unit = {},
    val loadArtifactPreview: (ArtifactRef) -> Unit = {},
)

@Composable
fun WorkbenchPane(
    state: WorkbenchState,
    actions: WorkbenchActions,
    selectedArtifact: ArtifactRef? = null,
    artifactPreview: LoadedArtifactPreview? = null,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedRunId by remember(state.activeRunId) { mutableStateOf(state.activeRunId) }
    var localArtifact by remember { mutableStateOf<ArtifactRef?>(null) }
    val currentArtifact = selectedArtifact ?: localArtifact

    Column(modifier.fillMaxSize()) {
        Text(
            "Agent workbench",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        AgentRuns(
            runs = state.runs.values.sortedWith(compareBy<AgentRun> { it.parentId != null }.thenBy { it.title }),
            selectedRunId = selectedRunId,
            onSelect = { run ->
                selectedRunId = run.id
                actions.selectAgentRun(run.id)
            },
        )
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Activity") },
                modifier = Modifier.semantics { contentDescription = "Activity" },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Artifacts") },
                modifier = Modifier.semantics { contentDescription = "Artifacts" },
            )
        }
        if (selectedTab == 0) {
            ActivityList(state, selectedRunId, Modifier.weight(1f))
        } else {
            ArtifactList(
                artifacts = state.artifacts.values.sortedBy { it.name },
                selectedArtifact = currentArtifact,
                onSelect = { artifact ->
                    localArtifact = artifact
                    actions.selectArtifact(artifact)
                    actions.loadArtifactPreview(artifact)
                },
                modifier = Modifier.weight(1f),
            )
            ArtifactPreviewPane(
                artifact = currentArtifact,
                preview = artifactPreview,
                onLoad = actions.loadArtifactPreview,
                modifier = Modifier.weight(1.2f),
            )
        }
    }
}

@Composable
private fun AgentRuns(runs: List<AgentRun>, selectedRunId: String?, onSelect: (AgentRun) -> Unit) {
    if (runs.isEmpty()) {
        Text("No active Agent run.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        return
    }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        runs.forEach { run ->
            val selected = selectedRunId == run.id
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(run) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (run.parentId == null) run.title else "↳ ${run.title}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(runStateLabel(run.state), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ActivityList(state: WorkbenchState, selectedRunId: String?, modifier: Modifier) {
    val activities = state.activities.values
        .filter { selectedRunId == null || it.runId == selectedRunId }
        .sortedBy { it.title }
    LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (activities.isEmpty()) item { Text("No activity recorded for this Agent yet.") }
        items(activities, key = { it.id }) { activity ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(activity.title, fontWeight = FontWeight.SemiBold)
                    Text("${activity.kind} · ${runStateLabel(activity.state)}", style = MaterialTheme.typography.bodySmall)
                    activity.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ArtifactList(
    artifacts: List<ArtifactRef>,
    selectedArtifact: ArtifactRef?,
    onSelect: (ArtifactRef) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier.widthIn(min = 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (artifacts.isEmpty()) item { Text("No artifacts yet.", modifier = Modifier.padding(12.dp)) }
        items(artifacts, key = { it.id }) { artifact ->
            val selected = selectedArtifact?.id == artifact.id
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { onSelect(artifact) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text("Open artifact ${artifact.name}", modifier = Modifier.padding(10.dp))
            }
        }
    }
}

private fun runStateLabel(state: AgentRunState): String = when (state) {
    AgentRunState.Running -> "Running"
    AgentRunState.WaitingApproval -> "Waiting for approval"
    AgentRunState.Completed -> "Completed"
    AgentRunState.Failed -> "Failed"
    AgentRunState.Unknown -> "Unknown"
}
