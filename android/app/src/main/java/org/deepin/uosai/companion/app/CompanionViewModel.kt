package org.deepin.uosai.companion.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.deepin.uosai.companion.core.network.CompanionSocket
import org.deepin.uosai.companion.core.network.CompanionWebSocket
import org.deepin.uosai.companion.core.network.ConnectionState
import org.deepin.uosai.companion.core.pairing.PairingUri
import org.deepin.uosai.companion.core.protocol.CommandFrame
import org.deepin.uosai.companion.core.protocol.ConversationFrame
import org.deepin.uosai.companion.core.protocol.InboundFrame
import org.deepin.uosai.companion.core.protocol.RemoteEvent
import org.deepin.uosai.companion.core.protocol.RemoteEventFrame
import org.deepin.uosai.companion.core.protocol.WorkbenchFrame
import org.deepin.uosai.companion.core.security.KeystoreDeviceGrantStore

data class TranscriptEntry(val id: String, val role: TranscriptRole, val text: String)
enum class TranscriptRole { User, Assistant, System }
data class AgentApproval(val id: String, val actionType: String, val title: String, val details: JsonObject)

internal data class ConversationSnapshotProjection(
    val transcript: List<TranscriptEntry>,
    val workbench: WorkbenchState,
    val content: ConversationContent,
    val taskStatus: TaskStatus?,
)

internal fun conversationSnapshotProjection(result: JsonObject): ConversationSnapshotProjection {
    val sequence = result["sequence"].longValue() ?: 0L
    return ConversationSnapshotProjection(
        transcript = transcriptEntries(result["render"] as? JsonObject),
        workbench = WorkbenchFrame.parseSnapshot(result["workbench"] as? JsonObject, sequence)
            ?: WorkbenchState(sequence = sequence),
        content = ConversationFrame.parseContent(result["content"] as? JsonObject),
        taskStatus = ConversationFrame.taskStatus(result.stringValue("taskStatus")),
    )
}

data class CompanionUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val pairedHostName: String? = null,
    val workspaces: List<CompanionWorkspace> = emptyList(),
    val conversations: List<CompanionConversation> = emptyList(),
    val selectedWorkspaceId: String? = null,
    val selectedConversation: CompanionConversation? = null,
    val creationOptions: ConversationCreationOptions? = null,
    val creatingConversation: Boolean = false,
    val transcript: List<TranscriptEntry> = emptyList(),
    val content: ConversationContent = ConversationContent(),
    val workbench: WorkbenchState = WorkbenchState(),
    val selectedAgentRunId: String? = null,
    val selectedArtifact: ArtifactRef? = null,
    val artifactPreview: LoadedArtifactPreview? = null,
    val activeTurn: Boolean = false,
    val pendingApproval: AgentApproval? = null,
    val errorMessage: String? = null,
) {
    val selectedWorkspace: CompanionWorkspace? get() = workspaces.firstOrNull { it.id == selectedWorkspaceId }
}

class CompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val socket: CompanionSocket = CompanionWebSocket(KeystoreDeviceGrantStore(application))
    private val mutableState = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = mutableState.asStateFlow()
    private var lastConsumedDeepLink: String? = null
    private var reloadingConversationId: String? = null

    init {
        viewModelScope.launch {
            socket.connectionState.collectLatest { connection ->
                val current = mutableState.value
                mutableState.value = current.copy(connection = connection)
                if (connection is ConnectionState.Connected && current.connection is ConnectionState.Reconnecting) {
                    loadWorkspaces(current.selectedConversation?.id)
                }
            }
        }
        viewModelScope.launch {
            socket.frames().collectLatest(::handleIncomingFrame)
        }
    }

    fun pair(rawInvitation: String) = viewModelScope.launch {
        runCatching {
            PairingUri.parse(rawInvitation.trim())
        }.onFailure {
            showError(it.message ?: "Pairing QR code is invalid.")
        }.onSuccess { invitation ->
            runCatching { socket.pair(invitation, Build.MODEL.ifBlank { "Android companion" }) }
                .onSuccess { grant ->
                    mutableState.value = mutableState.value.copy(
                        pairedHostName = grant.hostDisplayName,
                        errorMessage = null,
                    )
                    loadWorkspaces()
                }
                .onFailure { showError("Pairing failed. Confirm this device can reach the selected network and scan a fresh QR code.") }
        }
    }

    fun pairFromDeepLink(rawInvitation: String) {
        if (rawInvitation == lastConsumedDeepLink) return
        lastConsumedDeepLink = rawInvitation
        pair(rawInvitation)
    }

    fun reconnect() = viewModelScope.launch {
        val conversationId = mutableState.value.selectedConversation?.id
        runCatching { socket.connectSavedGrant() }
            .onSuccess { grant ->
                if (grant != null) {
                    mutableState.value = mutableState.value.copy(pairedHostName = grant.hostDisplayName, errorMessage = null)
                    loadWorkspaces(conversationId)
                }
            }
            .onFailure { showError("Unable to reconnect to UOS AI.") }
    }

    fun loadWorkspaces(resumeConversationId: String? = null) = viewModelScope.launch {
        runCatching {
            socket.request(CommandFrame.listWorkspaces(requestId()))
        }.onSuccess { frame ->
            val result = frame.commandResult() ?: return@onSuccess showFrameError(frame, "Unable to load shared workspaces.")
            val workspaces = (result["workspaces"] as? JsonArray).orEmpty().mapNotNull(::parseWorkspace)
            val conversations = flattenRecentConversations(workspaces)
            val current = mutableState.value
            mutableState.value = current.copy(
                workspaces = workspaces,
                conversations = conversations,
                selectedWorkspaceId = current.selectedWorkspaceId,
                errorMessage = null,
            )
            subscribeTo(conversations)
            resumeConversationId?.let { conversationId ->
                conversations.firstOrNull { it.id == conversationId }
                    ?.let(::openConversation)
            }
        }.onFailure { showError("Unable to load shared workspaces.") }
    }

    fun selectWorkspace(workspaceId: String?) {
        mutableState.value = mutableState.value.copy(selectedWorkspaceId = workspaceId)
    }

    fun openNewConversation() = viewModelScope.launch {
        runCatching {
            socket.request(CommandFrame.getConversationCreationOptions(requestId()))
        }.onSuccess { frame ->
            val result = frame.commandResult() ?: return@onSuccess showFrameError(frame, "Unable to load conversation choices.")
            mutableState.value = mutableState.value.copy(
                creationOptions = ConversationFrame.parseCreationOptions(result),
                errorMessage = null,
            )
        }.onFailure { showError("Unable to load conversation choices.") }
    }

    fun closeNewConversation() {
        mutableState.value = mutableState.value.copy(creationOptions = null, creatingConversation = false)
    }

    fun createConversation(workspaceId: String, assistantId: String, modelId: String) = viewModelScope.launch {
        if (workspaceId.isBlank() || assistantId.isBlank() || modelId.isBlank() || mutableState.value.creatingConversation) return@launch
        mutableState.value = mutableState.value.copy(creatingConversation = true)
        runCatching {
            socket.request(CommandFrame.createConversation(requestId(), workspaceId, assistantId, modelId))
        }.onSuccess { frame ->
            val result = frame.commandResult() ?: run {
                mutableState.value = mutableState.value.copy(creatingConversation = false)
                return@onSuccess showFrameError(frame, "Unable to create a conversation.")
            }
            val created = ConversationFrame.parseConversation(result, workspaceId)
                ?: run {
                    mutableState.value = mutableState.value.copy(creatingConversation = false)
                    return@onSuccess showError("UOS AI returned an invalid conversation.")
                }
            val current = mutableState.value
            val updatedWorkspaces = current.workspaces.map { workspace ->
                if (workspace.id == created.workspaceId) {
                    workspace.copy(conversations = addRecentConversation(workspace.conversations, created))
                } else {
                    workspace
                }
            }
            mutableState.value = current.copy(
                workspaces = updatedWorkspaces,
                conversations = addRecentConversation(current.conversations, created),
                selectedWorkspaceId = created.workspaceId,
                creationOptions = null,
                creatingConversation = false,
                errorMessage = null,
            )
            openConversation(created)
        }.onFailure {
            mutableState.value = mutableState.value.copy(creatingConversation = false)
            showError("Unable to create a conversation.")
        }
    }

    fun openConversation(conversation: CompanionConversation) {
        mutableState.value = mutableState.value.copy(
            selectedConversation = conversation,
            transcript = emptyList(),
            content = ConversationContent(),
            workbench = WorkbenchState(),
            selectedAgentRunId = null,
            selectedArtifact = null,
            artifactPreview = null,
            activeTurn = false,
            pendingApproval = null,
            errorMessage = null,
        )
        loadConversationSnapshot(conversation)
    }

    fun closeConversation() {
        mutableState.value = mutableState.value.copy(
            selectedConversation = null,
            transcript = emptyList(),
            content = ConversationContent(),
            workbench = WorkbenchState(),
            selectedAgentRunId = null,
            selectedArtifact = null,
            artifactPreview = null,
            pendingApproval = null,
        )
    }

    fun startTurn(message: String, assistantId: String = "uos-claw", modelId: String = "deepseek-chat") = viewModelScope.launch {
        val current = mutableState.value
        val conversation = current.selectedConversation ?: return@launch
        if (message.isBlank() || current.activeTurn) return@launch
        mutableState.value = current.copy(
            activeTurn = true,
            transcript = current.transcript + TranscriptEntry(requestId(), TranscriptRole.User, message),
        )
        runCatching {
            socket.send(
                CommandFrame.startTurn(
                    requestId(),
                    conversation.workspaceId.ifBlank { current.selectedWorkspace?.id.orEmpty() },
                    conversation.id,
                    message,
                    conversation.assistantId.ifBlank { assistantId },
                    conversation.modelId.ifBlank { modelId },
                ),
            )
        }.onFailure {
            mutableState.value = mutableState.value.copy(activeTurn = false)
            showError("Unable to send the message.")
        }
    }

    fun cancelTurn() = viewModelScope.launch {
        val conversation = mutableState.value.selectedConversation ?: return@launch
        runCatching { socket.send(CommandFrame.cancel(requestId(), conversation.id)) }
            .onFailure { showError("Unable to cancel this Agent turn.") }
    }

    fun answerApproval(approved: Boolean) = viewModelScope.launch {
        val approval = mutableState.value.pendingApproval ?: return@launch
        val conversation = mutableState.value.selectedConversation ?: return@launch
        mutableState.value = mutableState.value.copy(pendingApproval = null)
        runCatching { socket.send(CommandFrame.answerApproval(requestId(), conversation.id, approval.id, approved)) }
            .onFailure { showError("Unable to submit this Agent approval.") }
    }

    fun selectAgentRun(runId: String?) {
        mutableState.value = mutableState.value.copy(selectedAgentRunId = runId)
    }

    fun selectArtifact(artifact: ArtifactRef?) {
        mutableState.value = mutableState.value.copy(selectedArtifact = artifact, artifactPreview = null)
    }

    fun loadArtifactPreview(artifact: ArtifactRef) = viewModelScope.launch {
        val conversation = mutableState.value.selectedConversation ?: return@launch
        mutableState.value = mutableState.value.copy(
            selectedArtifact = artifact,
            artifactPreview = LoadedArtifactPreview(
                artifactId = artifact.id,
                previewKind = artifact.previewKind,
                state = "loading",
            ),
        )
        var expectedRevision = artifact.revision
        repeat(2) { attempt ->
            var accumulated: AccumulatedArtifactPreview? = null
            var cursor = JsonObject(emptyMap())
            while (true) {
                val frame = try {
                    socket.request(
                        CommandFrame.getArtifactPreview(
                            requestId = requestId(),
                            conversationId = conversation.id,
                            artifactId = artifact.id,
                            revision = expectedRevision,
                            cursor = cursor,
                        ),
                    )
                } catch (_: Throwable) {
                    mutableState.value = mutableState.value.copy(
                        artifactPreview = LoadedArtifactPreview(artifact.id, artifact.previewKind, "failed"),
                    )
                    return@launch
                }
                val preview = frame.commandResult()?.get("preview") as? JsonObject
                if (preview == null) {
                    mutableState.value = mutableState.value.copy(
                        artifactPreview = LoadedArtifactPreview(artifact.id, artifact.previewKind, "failed"),
                    )
                    return@launch
                }
                val page = preview.toArtifactPreviewPage()
                if (page.state != "ready") {
                    if (page.state == "stale_preview" && attempt == 0) {
                        expectedRevision = ""
                        break
                    }
                    mutableState.value = mutableState.value.copy(
                        artifactPreview = LoadedArtifactPreview(artifact.id, artifact.previewKind, page.state),
                    )
                    return@launch
                }
                when (val result = appendArtifactPreviewPage(accumulated, page)) {
                    ArtifactPreviewPageResult.Reload -> {
                        if (attempt == 0) {
                            expectedRevision = ""
                            break
                        }
                        mutableState.value = mutableState.value.copy(
                            artifactPreview = LoadedArtifactPreview(artifact.id, artifact.previewKind, "stale_preview"),
                        )
                        return@launch
                    }
                    is ArtifactPreviewPageResult.Continue -> {
                        accumulated = result.preview
                        if (!result.preview.truncated) {
                            mutableState.value = mutableState.value.copy(
                                artifactPreview = LoadedArtifactPreview(
                                    artifactId = artifact.id,
                                    previewKind = artifact.previewKind,
                                    state = "ready",
                                    revision = result.preview.revision,
                                    content = result.preview.content,
                                    truncated = false,
                                ),
                            )
                            return@launch
                        }
                        cursor = result.preview.nextCursor ?: run {
                            mutableState.value = mutableState.value.copy(
                                artifactPreview = LoadedArtifactPreview(artifact.id, artifact.previewKind, "failed"),
                            )
                            return@launch
                        }
                        expectedRevision = result.preview.revision
                    }
                }
            }
        }
        mutableState.value = mutableState.value.copy(
            artifactPreview = LoadedArtifactPreview(artifact.id, artifact.previewKind, "stale_preview"),
        )
    }

    fun dismissError() {
        mutableState.value = mutableState.value.copy(errorMessage = null)
    }

    private fun handleIncomingFrame(frame: InboundFrame) {
        when (frame) {
            is InboundFrame.Failure -> {
                mutableState.value = mutableState.value.copy(activeTurn = false, errorMessage = frame.error.message)
            }
            is InboundFrame.Event -> handleEvent(frame.event)
            else -> Unit
        }
    }

    private fun handleEvent(frame: RemoteEventFrame) {
        val current = mutableState.value
        val status = if (frame.event == RemoteEvent.TaskStatusDelta) {
            ConversationFrame.taskStatus(frame.payload.stringValue("taskStatus"))
        } else {
            null
        }
        val conversations = if (frame.event == RemoteEvent.TaskStatusDelta) {
            reduceConversationStatus(current.conversations, frame.conversationId, status, frame.sequence)
        } else {
            current.conversations
        }
        val selected = conversations.firstOrNull { it.id == current.selectedConversation?.id }
            ?: current.selectedConversation
        if (frame.conversationId != current.selectedConversation?.id) {
            if (conversations != current.conversations) {
                mutableState.value = current.copy(conversations = conversations, selectedConversation = selected)
            }
            return
        }
        val reduced = reduceWorkbench(current.workbench, frame)
        val reducedState = when (reduced) {
            is WorkbenchReduceResult.Applied -> reduced.state
            is WorkbenchReduceResult.Ignored -> return
            WorkbenchReduceResult.Reload -> {
                reloadConversationSnapshot(current.selectedConversation)
                return
            }
        }
        var next = current.copy(conversations = conversations, selectedConversation = selected, workbench = reducedState)
        if (frame.event == RemoteEvent.AgentRunDelta) {
            next = next.copy(activeTurn = reducedState.hasActiveRun())
        }
        when (frame.event) {
            RemoteEvent.MessageDelta -> frame.payload.textDelta()?.let { text ->
                val entry = TranscriptEntry("${frame.conversationId}-${frame.sequence}", TranscriptRole.Assistant, text)
                if (next.transcript.none { it.id == entry.id }) {
                    next = next.copy(transcript = next.transcript + entry)
                }
            }
            RemoteEvent.ApprovalRequested -> {
                frame.payload.stringValue("approvalId")?.let { approvalId ->
                    next = next.copy(
                        pendingApproval = AgentApproval(
                            id = approvalId,
                            actionType = frame.payload.stringValue("actionType") ?: "agent_action",
                            title = frame.payload.stringValue("title") ?: "Agent approval required",
                            details = frame.payload["details"] as? JsonObject ?: JsonObject(emptyMap()),
                        ),
                    )
                }
            }
            RemoteEvent.TurnFinished, RemoteEvent.TurnFailed -> {
                next = next.copy(activeTurn = false)
                reloadConversationSnapshot(next.selectedConversation)
            }
            else -> Unit
        }
        mutableState.value = next
    }

    private fun loadConversationSnapshot(conversation: CompanionConversation) = viewModelScope.launch {
        runCatching {
            socket.request(CommandFrame.getConversation(requestId(), conversation.id))
        }.onSuccess { frame ->
            val result = frame.commandResult() ?: return@onSuccess showFrameError(frame, "Unable to open this conversation.")
            applyConversationSnapshot(conversation, result)
            val sequence = mutableState.value.workbench.sequence
            runCatching {
                socket.request(CommandFrame.subscribe(requestId(), mapOf(conversation.id to sequence)))
            }.onFailure { showError("Unable to subscribe to conversation updates.") }
        }.onFailure { showError("Unable to open this conversation.") }
    }

    private fun reloadConversationSnapshot(conversation: CompanionConversation?) {
        val target = conversation ?: return
        if (reloadingConversationId == target.id) return
        reloadingConversationId = target.id
        viewModelScope.launch {
            try {
                val frame = socket.request(CommandFrame.getConversation(requestId(), target.id))
                val result = frame.commandResult() ?: return@launch showFrameError(frame, "Unable to refresh this conversation.")
                applyConversationSnapshot(target, result)
                socket.request(CommandFrame.subscribe(requestId(), mapOf(target.id to mutableState.value.workbench.sequence)))
            } catch (_: Throwable) {
                showError("Unable to refresh this conversation.")
            } finally {
                reloadingConversationId = null
            }
        }
    }

    private fun subscribeTo(conversations: List<CompanionConversation>) {
        if (conversations.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                socket.request(
                    CommandFrame.subscribe(
                        requestId(),
                        conversations.associate { conversation -> conversation.id to conversation.sequence },
                    ),
                )
            }.onFailure { showError("Unable to subscribe to conversation updates.") }
        }
    }

    private fun applyConversationSnapshot(conversation: CompanionConversation, result: JsonObject) {
        if (mutableState.value.selectedConversation?.id != conversation.id) return
        val snapshot = conversationSnapshotProjection(result)
        val refreshed = ConversationFrame.parseConversation(result, conversation.workspaceId)
            ?: conversation.copy(taskStatus = snapshot.taskStatus, sequence = snapshot.workbench.sequence)
        val conversations = addRecentConversation(mutableState.value.conversations, refreshed)
        mutableState.value = mutableState.value.copy(
            conversations = conversations,
            selectedConversation = refreshed,
            transcript = snapshot.transcript,
            workbench = snapshot.workbench,
            content = snapshot.content,
            activeTurn = snapshot.workbench.hasActiveRun(),
            errorMessage = null,
        )
    }

    private fun showFrameError(frame: InboundFrame, fallback: String) {
        val message = (frame as? InboundFrame.Failure)?.error?.message ?: fallback
        showError(message)
    }

    private fun showError(message: String) {
        mutableState.value = mutableState.value.copy(errorMessage = message)
    }

    override fun onCleared() {
        socket.close()
        super.onCleared()
    }

    private fun requestId(): String = UUID.randomUUID().toString()
}

private fun InboundFrame.commandResult(): JsonObject? = (this as? InboundFrame.Event)
    ?.takeIf { it.event.event == RemoteEvent.CommandAck }
    ?.event?.payload?.get("result") as? JsonObject

private fun parseWorkspace(value: JsonElement): CompanionWorkspace? {
    val objectValue = value as? JsonObject ?: return null
    val id = objectValue.stringValue("value") ?: return null
    val conversations = (objectValue["conversations"] as? JsonArray).orEmpty().mapNotNull { item ->
        val conversation = item as? JsonObject ?: return@mapNotNull null
        ConversationFrame.parseConversation(conversation, id)
    }
    return CompanionWorkspace(id, objectValue.stringValue("label") ?: id, conversations)
}

private fun transcriptEntries(render: JsonObject?): List<TranscriptEntry> {
    val messages = render?.get("messages") as? JsonObject ?: return emptyList()
    return messages.mapNotNull { (id, value) ->
        val message = value as? JsonObject ?: return@mapNotNull null
        val chunks = ((message["render_message"] as? JsonArray).orEmpty()).mapNotNull { renderItem ->
            ((renderItem as? JsonObject)?.get("data") as? JsonObject)?.stringValue("content")
        }
        val text = chunks.joinToString("\n").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val role = if (message["role"].longValue() == 1L) TranscriptRole.User else TranscriptRole.Assistant
        TranscriptEntry(id, role, text)
    }
}

private fun JsonObject.textDelta(): String? =
    ((get("data") as? JsonObject)?.stringValue("content") ?: stringValue("content"))?.takeIf { it.isNotBlank() }

private fun JsonObject.stringValue(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.longValue(): Long? = (this as? JsonPrimitive)?.longOrNull

private fun JsonObject.toArtifactPreviewPage(): ArtifactPreviewPage = ArtifactPreviewPage(
    state = stringValue("state") ?: "failed",
    revision = stringValue("revision") ?: "",
    content = get("content") as? JsonObject,
    truncated = (get("truncated") as? JsonPrimitive)?.booleanOrNull ?: false,
    nextCursor = get("next_cursor") as? JsonObject,
)

private fun WorkbenchState.hasActiveRun(): Boolean = activeRunId?.let { runId ->
    runs[runId]?.state == AgentRunState.Running || runs[runId]?.state == AgentRunState.WaitingApproval
} == true
