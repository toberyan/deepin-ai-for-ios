package org.deepin.uosai.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.deepin.uosai.companion.app.ConversationCreationOptions

@Composable
fun NewConversationPane(
    options: ConversationCreationOptions,
    submitting: Boolean,
    onCreate: (workspaceId: String, assistantId: String, modelId: String) -> Unit,
    onBack: () -> Unit,
) {
    var workspaceId by remember(options) { mutableStateOf<String?>(null) }
    var agentId by remember(options) { mutableStateOf<String?>(null) }
    var modelId by remember(agentId) { mutableStateOf<String?>(null) }
    val selectedAgent = options.agents.firstOrNull { it.id == agentId }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("New conversation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Choose where and how UOS AI should start this empty desktop conversation.")
        ChoiceSection("Choose workspace") {
            options.workspaces.forEach { workspace ->
                FilterChip(
                    selected = workspace.id == workspaceId,
                    onClick = { workspaceId = workspace.id },
                    label = { Text(workspace.label) },
                )
            }
        }
        ChoiceSection("Choose Agent") {
            options.agents.forEach { agent ->
                FilterChip(
                    selected = agent.id == agentId,
                    onClick = { agentId = agent.id },
                    label = { Text(agent.name) },
                )
            }
        }
        ChoiceSection("Choose model") {
            selectedAgent?.models?.forEach { model ->
                FilterChip(
                    selected = model.id == modelId,
                    onClick = { modelId = model.id },
                    label = { Text(model.name) },
                )
            }
            if (selectedAgent == null) Text("Select an Agent first.")
        }
        Button(
            enabled = !submitting && workspaceId != null && agentId != null && modelId != null,
            onClick = { onCreate(workspaceId.orEmpty(), agentId.orEmpty(), modelId.orEmpty()) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (submitting) "Creating…" else "Start conversation") }
    }
}

@Composable
private fun ChoiceSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
