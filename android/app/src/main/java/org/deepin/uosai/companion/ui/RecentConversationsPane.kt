package org.deepin.uosai.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.deepin.uosai.companion.app.CompanionConversation
import org.deepin.uosai.companion.app.TaskStatus

@Composable
fun RecentConversationsPane(
    conversations: List<CompanionConversation>,
    selectedWorkspaceId: String?,
    onWorkspaceFilter: (String?) -> Unit,
    onOpen: (CompanionConversation) -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = conversations.filter { selectedWorkspaceId == null || it.workspaceId == selectedWorkspaceId }
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Recent conversations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Continue a UOS AI conversation from your desktop.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Button(onClick = onNewConversation, modifier = Modifier.fillMaxWidth()) { Text("New conversation") }
        }
        if (selectedWorkspaceId != null) {
            item {
                TextButton(onClick = { onWorkspaceFilter(null) }) { Text("Show all conversations") }
            }
        }
        if (visible.isEmpty()) {
            item { Text("No recent conversations yet.", modifier = Modifier.padding(top = 24.dp)) }
        }
        items(visible, key = CompanionConversation::id) { conversation ->
            ConversationRow(conversation, onOpen)
        }
    }
}

@Composable
private fun ConversationRow(conversation: CompanionConversation, onOpen: (CompanionConversation) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        TextButton(onClick = { onOpen(conversation) }, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(conversation.workspaceId, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ConversationStatusLabel(conversation.taskStatus)
            }
        }
    }
}

@Composable
private fun ConversationStatusLabel(status: TaskStatus?) {
    val label = when (status) {
        TaskStatus.Running -> "Running"
        TaskStatus.AwaitingApproval -> "Waiting for approval"
        TaskStatus.Completed -> "Completed"
        TaskStatus.Failed -> "Failed"
        null -> return
    }
    Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 0.dp))
}
