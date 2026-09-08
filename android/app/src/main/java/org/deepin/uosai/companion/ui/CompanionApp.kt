package org.deepin.uosai.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.deepin.uosai.companion.app.AgentApproval
import org.deepin.uosai.companion.app.CompanionConversation
import org.deepin.uosai.companion.app.CompanionUiState
import org.deepin.uosai.companion.app.CompanionViewModel
import org.deepin.uosai.companion.app.CompanionWorkspace
import org.deepin.uosai.companion.app.TranscriptEntry
import org.deepin.uosai.companion.app.TranscriptRole
import org.deepin.uosai.companion.core.network.ConnectionState
import org.deepin.uosai.companion.feature.pairing.PairingEntryMode
import org.deepin.uosai.companion.feature.pairing.QrScannerDialog
import org.deepin.uosai.companion.feature.pairing.showManualEntry
import org.deepin.uosai.companion.feature.pairing.showScanner

private val wideLayoutBreakpoint = 840.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionApp(state: CompanionUiState, model: CompanionViewModel) {
    if (state.pairedHostName == null && state.workspaces.isEmpty()) {
        PairingScreen(
            connection = state.connection,
            error = state.errorMessage,
            onPair = model::pair,
            onDismissError = model::dismissError,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.pairedHostName ?: "UOS AI Companion") },
                actions = {
                    AssistChip(
                        onClick = model::reconnect,
                        label = { Text(connectionLabel(state.connection)) },
                    )
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            state.errorMessage?.let { error ->
                ErrorBanner(error, model::dismissError)
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= wideLayoutBreakpoint
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        WorkspacePane(
                            workspaces = state.workspaces,
                            selectedWorkspaceId = state.selectedWorkspaceId,
                            selectedConversation = state.selectedConversation,
                            onWorkspace = model::selectWorkspace,
                            onConversation = model::openConversation,
                            modifier = Modifier.width(360.dp).fillMaxHeight(),
                        )
                        VerticalDivider()
                        ConversationPane(
                            state = state,
                            onSend = model::startTurn,
                            onCancel = model::cancelTurn,
                            onBack = null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (state.selectedConversation == null) {
                    WorkspacePane(
                        workspaces = state.workspaces,
                        selectedWorkspaceId = state.selectedWorkspaceId,
                        selectedConversation = null,
                        onWorkspace = model::selectWorkspace,
                        onConversation = model::openConversation,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    ConversationPane(
                        state = state,
                        onSend = model::startTurn,
                        onCancel = model::cancelTurn,
                        onBack = model::closeConversation,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    state.pendingApproval?.let { approval ->
        ApprovalSheet(
            approval = approval,
            onAnswer = model::answerApproval,
        )
    }
}

@Composable
private fun PairingScreen(
    connection: ConnectionState,
    error: String?,
    onPair: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    var invitation by rememberSaveable { mutableStateOf("") }
    var showScanner by rememberSaveable { mutableStateOf(false) }
    var entryMode by remember { mutableStateOf(PairingEntryMode.initial()) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 620.dp).padding(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Connect UOS AI", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Scan the one-time QR code from UOS AI desktop. This companion only accepts secure Tailscale .ts.net invitations.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entryMode == PairingEntryMode.Scanner) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Scan desktop QR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("On UOS AI desktop, open Mobile Companion and choose Show pairing QR.")
                            Button(onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth()) { Text("Scan UOS AI QR") }
                            TextButton(onClick = { entryMode = entryMode.showManualEntry() }, modifier = Modifier.align(Alignment.End)) {
                                Text("Paste invitation instead")
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = invitation,
                        onValueChange = { invitation = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pairing invitation") },
                        minLines = 3,
                        maxLines = 5,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onPair(invitation) }, enabled = invitation.isNotBlank()) { Text("Pair") }
                        OutlinedButton(onClick = { entryMode = entryMode.showScanner() }) { Text("Scan QR instead") }
                    }
                }
                Text(connectionLabel(connection), style = MaterialTheme.typography.bodySmall)
                error?.let { ErrorBanner(it, onDismissError) }
            }
        }
    }
    if (showScanner) {
        QrScannerDialog(
            onPayload = { payload ->
                invitation = payload
                showScanner = false
                onPair(payload)
            },
            onDismiss = { showScanner = false },
        )
    }
}

@Composable
private fun WorkspacePane(
    workspaces: List<CompanionWorkspace>,
    selectedWorkspaceId: String?,
    selectedConversation: CompanionConversation?,
    onWorkspace: (String) -> Unit,
    onConversation: (CompanionConversation) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Shared workspaces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Only conversations explicitly shared by UOS AI are shown here.", style = MaterialTheme.typography.bodySmall)
        }
        if (workspaces.isEmpty()) item { Text("No shared workspaces yet.", modifier = Modifier.padding(top = 24.dp)) }
        items(workspaces, key = { it.id }) { workspace ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (workspace.id == selectedWorkspaceId) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    TextButton(onClick = { onWorkspace(workspace.id) }) {
                        Text(workspace.label, fontWeight = FontWeight.SemiBold)
                    }
                    workspace.conversations.forEach { conversation ->
                        val selected = conversation.id == selectedConversation?.id
                        TextButton(
                            onClick = {
                                onWorkspace(workspace.id)
                                onConversation(conversation)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                conversation.title,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (workspace.conversations.isEmpty()) Text("No shared conversations", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ConversationPane(
    state: CompanionUiState,
    onSend: (String, String, String) -> Unit,
    onCancel: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val conversation = state.selectedConversation
    if (conversation == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Select a shared conversation to continue it here.") }
        return
    }
    var draft by remember(conversation.id) { mutableStateOf("") }
    var assistantId by rememberSaveable { mutableStateOf("uos-claw") }
    var modelId by rememberSaveable { mutableStateOf("deepseek-chat") }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { TextButton(onClick = it) { Text("Back") } }
            Text(conversation.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            if (state.activeTurn) OutlinedButton(onClick = onCancel) { Text("Cancel Agent") }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.transcript, key = { it.id }) { entry -> TranscriptBubble(entry) }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(assistantId, { assistantId = it }, label = { Text("Assistant") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(modelId, { modelId = it }, label = { Text("Model") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    draft,
                    { draft = it },
                    label = { Text("Continue this conversation") },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = draft.isNotBlank() && !state.activeTurn,
                    onClick = {
                        val message = draft
                        draft = ""
                        onSend(message, assistantId, modelId)
                    },
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry) {
    val user = entry.role == TranscriptRole.User
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 680.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(if (user) "You" else "UOS AI", style = MaterialTheme.typography.labelSmall)
                Text(entry.text)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(approval: AgentApproval, onAnswer: (Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = { /* one-time approval remains visible until answered */ }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Agent approval required", style = MaterialTheme.typography.headlineSmall)
            Text(approval.title, style = MaterialTheme.typography.titleMedium)
            Text("Action: ${approval.actionType}")
            if (approval.details.isNotEmpty()) {
                Text(approval.details.toString(), style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f)) { Text("Reject") }
                Button(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f)) { Text("Approve") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Connected -> "Connected"
    ConnectionState.AuthenticationRequired -> "Pair again"
    is ConnectionState.Reconnecting -> "Retrying ${state.attempt}/4"
}
