package org.deepin.uosai.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import org.deepin.uosai.companion.app.ArtifactRef
import org.deepin.uosai.companion.app.CompanionConversation
import org.deepin.uosai.companion.app.CompanionUiState
import org.deepin.uosai.companion.app.CompanionViewModel
import org.deepin.uosai.companion.app.TaskStatus
import org.deepin.uosai.companion.app.TranscriptEntry
import org.deepin.uosai.companion.app.TranscriptRole
import org.deepin.uosai.companion.core.network.ConnectionState
import org.deepin.uosai.companion.feature.pairing.PairingEntryMode
import org.deepin.uosai.companion.feature.pairing.PairingInstructions
import org.deepin.uosai.companion.feature.pairing.QrScannerDialog
import org.deepin.uosai.companion.feature.pairing.showManualEntry
import org.deepin.uosai.companion.feature.pairing.showScanner
import org.deepin.uosai.companion.ui.render.ArtifactPreviewPane
import org.deepin.uosai.companion.ui.render.RenderBlockContent

data class CompanionActions(
    val reconnect: () -> Unit,
    val pair: (String) -> Unit,
    val dismissError: () -> Unit,
    val selectWorkspace: (String?) -> Unit,
    val openConversation: (CompanionConversation) -> Unit,
    val closeConversation: () -> Unit,
    val openNewConversation: () -> Unit,
    val closeNewConversation: () -> Unit,
    val createConversation: (String, String, String) -> Unit,
    val startTurn: (String) -> Unit,
    val cancelTurn: () -> Unit,
    val answerApproval: (Boolean) -> Unit,
    val selectArtifact: (ArtifactRef?) -> Unit = {},
    val loadArtifactPreview: (ArtifactRef) -> Unit = {},
)

@Composable
fun CompanionApp(state: CompanionUiState, model: CompanionViewModel) {
    CompanionContent(
        state = state,
        actions = CompanionActions(
            reconnect = model::reconnect,
            pair = model::pair,
            dismissError = model::dismissError,
            selectWorkspace = model::selectWorkspace,
            openConversation = model::openConversation,
            closeConversation = model::closeConversation,
            openNewConversation = model::openNewConversation,
            closeNewConversation = model::closeNewConversation,
            createConversation = model::createConversation,
            startTurn = model::startTurn,
            cancelTurn = model::cancelTurn,
            answerApproval = model::answerApproval,
            selectArtifact = model::selectArtifact,
            loadArtifactPreview = model::loadArtifactPreview,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionContent(state: CompanionUiState, actions: CompanionActions) {
    if (state.pairedHostName == null && state.conversations.isEmpty()) {
        PairingScreen(state.connection, state.errorMessage, actions.pair, actions.dismissError)
        return
    }

    if (state.creationOptions != null) {
        NewConversationPane(
            options = state.creationOptions,
            submitting = state.creatingConversation,
            onCreate = actions.createConversation,
            onBack = actions.closeNewConversation,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth.value >= 840f) TabletCompanionLayout(state, actions)
        else PhoneCompanionLayout(state, actions)
    }

    state.pendingApproval?.let { ApprovalSheet(it, actions.answerApproval) }
    state.selectedArtifact?.let { artifact ->
        ModalBottomSheet(onDismissRequest = { actions.selectArtifact(null) }) {
            ArtifactPreviewPane(
                artifact = artifact,
                preview = state.artifactPreview,
                onLoad = actions.loadArtifactPreview,
                modifier = Modifier.fillMaxWidth().height(520.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletCompanionLayout(state: CompanionUiState, actions: CompanionActions) {
    PermanentNavigationDrawer(
        drawerContent = {
            PermanentDrawerSheet(Modifier.width(320.dp), windowInsets = WindowInsets.safeDrawing) {
                RecentConversationsPane(
                    conversations = state.conversations,
                    selectedWorkspaceId = state.selectedWorkspaceId,
                    onWorkspaceFilter = actions.selectWorkspace,
                    onOpen = actions.openConversation,
                    onNewConversation = actions.openNewConversation,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    ) {
        Scaffold(topBar = { CompanionTopBar(state, actions.reconnect) }) { contentPadding ->
            Column(Modifier.fillMaxSize().padding(contentPadding)) {
                state.errorMessage?.let { ErrorBanner(it, actions.dismissError) }
                ConversationPane(
                    state = state,
                    onSend = actions.startTurn,
                    onCancel = actions.cancelTurn,
                    onBack = null,
                    onOpenArtifact = { openInlineArtifact(state, actions, it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneCompanionLayout(state: CompanionUiState, actions: CompanionActions) {
    Scaffold(topBar = { CompanionTopBar(state, actions.reconnect) }) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            state.errorMessage?.let { ErrorBanner(it, actions.dismissError) }
            if (state.selectedConversation == null) {
                RecentConversationsPane(
                    conversations = state.conversations,
                    selectedWorkspaceId = state.selectedWorkspaceId,
                    onWorkspaceFilter = actions.selectWorkspace,
                    onOpen = actions.openConversation,
                    onNewConversation = actions.openNewConversation,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ConversationPane(
                    state = state,
                    onSend = actions.startTurn,
                    onCancel = actions.cancelTurn,
                    onBack = actions.closeConversation,
                    onOpenArtifact = { openInlineArtifact(state, actions, it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionTopBar(state: CompanionUiState, reconnect: () -> Unit) {
    TopAppBar(
        title = { Text(state.pairedHostName ?: "UOS AI Companion") },
        actions = {
            AssistChip(onClick = reconnect, label = { Text(connectionLabel(state.connection)) })
            Spacer(Modifier.width(8.dp))
        },
        windowInsets = WindowInsets.safeDrawing,
    )
}

private fun openInlineArtifact(state: CompanionUiState, actions: CompanionActions, artifactId: String) {
    state.content.artifacts[artifactId]?.let { artifact ->
        actions.selectArtifact(artifact)
        actions.loadArtifactPreview(artifact)
    }
}

@Composable
private fun ConversationPane(
    state: CompanionUiState,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenArtifact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversation = state.selectedConversation
    if (conversation == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Choose a recent conversation or start a new one.") }
        return
    }
    var draft by remember(conversation.id) { mutableStateOf("") }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let { TextButton(onClick = it) { Text("Back") } }
            Text(
                conversation.title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TaskStatusChip(conversation.taskStatus)
            if (state.activeTurn) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.transcript, key = TranscriptEntry::id) { TranscriptBubble(it) }
            items(state.content.blocks, key = { it.id }) { block ->
                RenderBlockContent(block = block, onOpenArtifact = onOpenArtifact)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Continue this conversation") },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = draft.isNotBlank() && !state.activeTurn,
                onClick = { val message = draft; draft = ""; onSend(message) },
            ) { Text("Send") }
        }
    }
}

@Composable
private fun TaskStatusChip(status: TaskStatus?) {
    val label = when (status) {
        TaskStatus.Running -> "Running"
        TaskStatus.AwaitingApproval -> "Waiting for approval"
        TaskStatus.Completed -> "Completed"
        TaskStatus.Failed -> "Failed"
        null -> return
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

@Composable
private fun TranscriptBubble(entry: TranscriptEntry) {
    val user = entry.role == TranscriptRole.User
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 680.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (user) androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(if (user) "You" else "UOS AI", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                Text(entry.text)
            }
        }
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
                Text("Connect UOS AI", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                Text(PairingInstructions.summary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                if (entryMode == PairingEntryMode.Scanner) {
                    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Scan desktop QR", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                Text(connectionLabel(connection), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                error?.let { ErrorBanner(it, onDismissError) }
            }
        }
    }
    if (showScanner) {
        QrScannerDialog(
            onPayload = { payload -> invitation = payload; showScanner = false; onPair(payload) },
            onDismiss = { showScanner = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(approval: AgentApproval, onAnswer: (Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = { }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Approval required", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Text(approval.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            if (approval.details.isNotEmpty()) {
                Text(approval.details.toString(), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
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
        colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
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
