package org.deepin.uosai.companion.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.deepin.uosai.companion.app.ArtifactRef
import org.deepin.uosai.companion.app.CompanionConversation
import org.deepin.uosai.companion.app.ConversationContent
import org.deepin.uosai.companion.app.ConversationCreationOptions
import org.deepin.uosai.companion.app.CreationAgent
import org.deepin.uosai.companion.app.CreationModel
import org.deepin.uosai.companion.app.CreationWorkspace
import org.deepin.uosai.companion.app.PreviewKind
import org.deepin.uosai.companion.app.RenderBlock
import org.deepin.uosai.companion.app.TaskStatus

object RemoteProtocol {
    const val major = 1
    const val minor = 0
}

@Serializable
data class ProtocolVersion(val major: Int = RemoteProtocol.major, val minor: Int = RemoteProtocol.minor)

@Serializable
data class CommandFrame(
    val kind: String = "command",
    val protocol: ProtocolVersion = ProtocolVersion(),
    val requestId: String,
    val command: String,
    val payload: JsonObject,
) {
    companion object {
        fun listWorkspaces(requestId: String) = CommandFrame(requestId = requestId, command = "list_workspaces", payload = buildJsonObject { })

        fun getConversationCreationOptions(requestId: String) = CommandFrame(
            requestId = requestId,
            command = "get_conversation_creation_options",
            payload = buildJsonObject { },
        )

        fun createConversation(
            requestId: String,
            workspaceId: String,
            assistantId: String,
            modelId: String,
        ) = CommandFrame(
            requestId = requestId,
            command = "create_conversation",
            payload = buildJsonObject {
                put("workspaceId", workspaceId)
                put("assistantId", assistantId)
                put("modelId", modelId)
            },
        )

        fun getConversation(requestId: String, conversationId: String) = CommandFrame(
            requestId = requestId,
            command = "get_conversation",
            payload = buildJsonObject { put("conversationId", conversationId) },
        )

        fun subscribe(requestId: String, cursors: Map<String, Long>) = CommandFrame(
            requestId = requestId,
            command = "subscribe",
            payload = buildJsonObject {
                put("cursors", buildJsonArray {
                    cursors.forEach { (conversationId, sequence) ->
                        add(buildJsonObject {
                            put("conversationId", conversationId)
                            put("sequence", sequence)
                        })
                    }
                })
            },
        )

        fun startTurn(
            requestId: String,
            workspaceId: String,
            conversationId: String,
            message: String,
            assistantId: String = "",
            modelId: String = "",
            attachments: JsonObject = buildJsonObject { },
        ) = CommandFrame(
            requestId = requestId,
            command = "start_turn",
            payload = buildJsonObject {
                put("workspaceId", workspaceId)
                put("conversationId", conversationId)
                put("message", message)
                put("assistantId", assistantId)
                put("modelId", modelId)
                put("attachments", attachments)
            },
        )

        fun cancel(requestId: String, conversationId: String) = CommandFrame(
            requestId = requestId,
            command = "cancel",
            payload = buildJsonObject { put("conversationId", conversationId) },
        )

        fun answerApproval(requestId: String, conversationId: String, approvalId: String, approved: Boolean) = CommandFrame(
            requestId = requestId,
            command = "approval",
            payload = buildJsonObject {
                put("conversationId", conversationId)
                put("approvalId", approvalId)
                put("approved", approved)
            },
        )

        fun getArtifactPreview(
            requestId: String,
            conversationId: String,
            artifactId: String,
            revision: String = "",
            cursor: JsonObject = buildJsonObject { },
        ) = CommandFrame(
            requestId = requestId,
            command = "get_artifact_preview",
            payload = buildJsonObject {
                put("conversationId", conversationId)
                put("artifactId", artifactId)
                if (revision.isNotBlank()) put("revision", revision)
                put("cursor", cursor)
            },
        )
    }
}

@Serializable
data class PairFrame(
    val kind: String = "pair",
    val protocol: ProtocolVersion = ProtocolVersion(),
    val payload: PairPayload,
)

@Serializable
data class PairPayload(val pairingSecret: String, val deviceName: String)

@Serializable
data class AuthenticateFrame(
    val kind: String = "authenticate",
    val protocol: ProtocolVersion = ProtocolVersion(),
    val deviceId: String,
    val token: String,
)

@Serializable
data class PairingGrant(val deviceId: String, val token: String, val displayName: String, val allowedWorkspaceIds: List<String>)
data class RemoteError(val code: String, val message: String, val details: JsonObject)

sealed interface RemoteEvent {
    data object Snapshot : RemoteEvent
    data object MessageDelta : RemoteEvent
    data object StateDelta : RemoteEvent
    data object ApprovalRequested : RemoteEvent
    data object TurnFinished : RemoteEvent
    data object TurnFailed : RemoteEvent
    data object CommandAck : RemoteEvent
    data object AgentRunDelta : RemoteEvent
    data object AgentActivityDelta : RemoteEvent
    data object ArtifactDelta : RemoteEvent
    data object TaskStatusDelta : RemoteEvent
    data class Unknown(val wireName: String) : RemoteEvent

    companion object {
        fun fromWireName(wireName: String): RemoteEvent = when (wireName) {
            "snapshot" -> Snapshot
            "message_delta" -> MessageDelta
            "state_delta" -> StateDelta
            "agent_approval_requested" -> ApprovalRequested
            "turn_finished" -> TurnFinished
            "turn_failed" -> TurnFailed
            "command_ack" -> CommandAck
            "agent_run_delta" -> AgentRunDelta
            "agent_activity_delta" -> AgentActivityDelta
            "artifact_delta" -> ArtifactDelta
            "task_status_delta" -> TaskStatusDelta
            else -> Unknown(wireName)
        }
    }
}

object ConversationFrame {
    fun taskStatus(value: String?): TaskStatus? = when (value) {
        "running" -> TaskStatus.Running
        "awaiting_approval" -> TaskStatus.AwaitingApproval
        "completed" -> TaskStatus.Completed
        "failed" -> TaskStatus.Failed
        else -> null
    }

    fun parseConversation(value: JsonObject, fallbackWorkspaceId: String = ""): CompanionConversation? {
        val id = value.string("id") ?: value.string("conversationId") ?: return null
        return CompanionConversation(
            id = id,
            title = value.string("title") ?: "Conversation",
            workspaceId = value.string("workspaceId") ?: fallbackWorkspaceId,
            updatedAt = value.long("updatedAt")
                ?: value.long("updated_at")
                ?: 0L,
            assistantId = value.string("assistantId") ?: "",
            modelId = value.string("modelId") ?: "",
            taskStatus = taskStatus(value.string("taskStatus")),
            sequence = value.long("sequence") ?: 0L,
        )
    }

    fun parseCreationOptions(value: JsonObject): ConversationCreationOptions = ConversationCreationOptions(
        workspaces = value.objects("workspaces").mapNotNull { workspace ->
            workspace.string("id")?.let { id -> CreationWorkspace(id, workspace.string("label") ?: id) }
        },
        agents = value.objects("agents").mapNotNull { agent ->
            agent.string("id")?.let { id ->
                CreationAgent(
                    id = id,
                    name = agent.string("name") ?: id,
                    models = agent.objects("models").mapNotNull { model ->
                        model.string("id")?.let { modelId -> CreationModel(modelId, model.string("name") ?: modelId) }
                    },
                )
            }
        },
    )

    fun parseContent(value: JsonObject?): ConversationContent {
        if (value == null) return ConversationContent()
        val artifacts = value.objects("artifacts").mapNotNull(::parseArtifact).associateBy(ArtifactRef::id)
        return ConversationContent(
            blocks = value.objects("blocks").mapNotNull(::parseRenderBlock),
            artifacts = artifacts,
        )
    }

    private fun parseArtifact(value: JsonObject): ArtifactRef? {
        val id = value.string("id") ?: return null
        val name = value.string("name") ?: return null
        return ArtifactRef(
            id = id,
            name = name,
            previewKind = previewKind(value.string("previewKind")),
            revision = value.string("revision") ?: "",
        )
    }

    private fun parseRenderBlock(value: JsonObject): RenderBlock? {
        val id = value.string("id") ?: return null
        val kind = value.string("kind") ?: return null
        val payload = value["payload"] as? JsonObject ?: return null
        return when (kind) {
            "markdown" -> payload.string("text")?.let { RenderBlock.Markdown(id, it) }
            "code" -> payload.string("text")?.let { RenderBlock.Code(id, payload.string("language") ?: "", it) }
            "image" -> payload.string("base64")?.let { base64 ->
                payload.string("mimeType")?.let { RenderBlock.Image(id, base64, it) }
            }
            "file_reference" -> payload.string("artifactId")?.let { artifactId ->
                RenderBlock.FileReference(id, artifactId, payload.string("label") ?: "Artifact")
            }
            else -> RenderBlock.Unsupported(id, kind)
        }
    }

    private fun previewKind(value: String?): PreviewKind = when (value) {
        "text" -> PreviewKind.Text
        "image" -> PreviewKind.Image
        "static_markup" -> PreviewKind.StaticMarkup
        "html_document" -> PreviewKind.HtmlDocument
        "file_reference" -> PreviewKind.FileReference
        else -> PreviewKind.Unknown
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.long(name: String): Long? =
        (get(name) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.objects(name: String): List<JsonObject> =
        (get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
}

data class RemoteEventFrame(
    val conversationId: String,
    val sequence: Long,
    val event: RemoteEvent,
    val payload: JsonObject,
)

sealed interface InboundFrame {
    data class PairingGranted(val grant: PairingGrant) : InboundFrame
    data class Authenticated(val deviceId: String, val displayName: String) : InboundFrame
    data class Event(val event: RemoteEventFrame) : InboundFrame
    data class Failure(val requestId: String?, val error: RemoteError) : InboundFrame
    data class Unknown(val kind: String, val payload: JsonObject) : InboundFrame
}

object RemoteJson {
    val codec = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(frame: CommandFrame): String = codec.encodeToString(frame)
    fun encode(frame: PairFrame): String = codec.encodeToString(frame)
    fun encode(frame: AuthenticateFrame): String = codec.encodeToString(frame)
}

object RemoteFrame {
    fun parseInbound(raw: String): InboundFrame {
        val root = RemoteJson.codec.parseToJsonElement(raw).jsonObject
        val kind = root.string("kind") ?: throw IllegalArgumentException("Inbound frame has no kind")
        val protocol = root["protocol"]?.jsonObject
        if (protocol?.get("major")?.jsonPrimitive?.intOrNull != RemoteProtocol.major) {
            throw IllegalArgumentException("Unsupported remote protocol")
        }
        return when (kind) {
            "pairing_granted" -> InboundFrame.PairingGranted(root.pairingGrant())
            "authenticated" -> {
                val payload = root.objectValue("payload")
                InboundFrame.Authenticated(payload.requiredString("deviceId"), payload.string("displayName") ?: "")
            }
            "event" -> InboundFrame.Event(
                RemoteEventFrame(
                    conversationId = root.string("conversationId") ?: "",
                    sequence = root["sequence"]?.jsonPrimitive?.longOrNull ?: 0,
                    event = RemoteEvent.fromWireName(root.requiredString("event")),
                    payload = root.objectValue("payload"),
                ),
            )
            "error" -> {
                val error = root.objectValue("error")
                InboundFrame.Failure(
                    requestId = root.string("requestId"),
                    error = RemoteError(error.requiredString("code"), error.requiredString("message"), error.objectValue("details")),
                )
            }
            else -> InboundFrame.Unknown(kind, root["payload"] as? JsonObject ?: buildJsonObject { })
        }
    }

    private fun JsonObject.pairingGrant(): PairingGrant {
        val payload = objectValue("payload")
        return PairingGrant(
            deviceId = payload.requiredString("deviceId"),
            token = payload.requiredString("token"),
            displayName = payload.string("displayName") ?: "",
            allowedWorkspaceIds = (payload["allowedWorkspaceIds"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty(),
        )
    }

    private fun JsonObject.objectValue(name: String): JsonObject =
        get(name) as? JsonObject ?: throw IllegalArgumentException("$name must be an object")

    private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$name is required")
}
