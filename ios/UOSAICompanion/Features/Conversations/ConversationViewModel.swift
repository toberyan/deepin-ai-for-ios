import Foundation

struct CompanionWorkspace: Identifiable, Equatable {
    let id: String
    let label: String
    let conversations: [CompanionConversation]
}

struct CompanionConversation: Identifiable, Equatable {
    let id: String
    let title: String
}

struct TranscriptEntry: Identifiable, Equatable {
    enum Role: String, Equatable {
        case user
        case assistant
        case system
    }

    let id: String
    let role: Role
    let text: String
}

struct AgentApproval: Identifiable, Equatable {
    let id: String
    let actionType: String
    let title: String
    let details: [String: JSONValue]
}

@MainActor
final class ConversationViewModel: ObservableObject {
    @Published private(set) var workspaces: [CompanionWorkspace] = []
    @Published private(set) var selectedWorkspace: CompanionWorkspace?
    @Published private(set) var selectedConversation: CompanionConversation?
    @Published private(set) var transcript: [TranscriptEntry] = []
    @Published private(set) var activeTurn = false
    @Published var pendingApproval: AgentApproval?
    @Published var errorMessage: String?

    private let client: CompanionWebSocket
    private var receiveTask: Task<Void, Never>?

    init(client: CompanionWebSocket) {
        self.client = client
    }

    deinit { receiveTask?.cancel() }

    func loadWorkspaces() async {
        do {
            let frame = try await client.request(CommandFrame(
                requestId: UUID().uuidString,
                command: .listWorkspaces,
                payload: [:],
            ))
            guard case let .event(event) = frame,
                  let result = event.payload["result"]?.objectValue,
                  let blocks = result["workspaces"]?.arrayValue else {
                try consumeFailure(frame)
                return
            }
            workspaces = blocks.compactMap(parseWorkspace)
            selectedWorkspace = workspaces.first
            errorMessage = nil
        } catch {
            errorMessage = "Unable to load shared workspaces."
        }
    }

    func select(workspace: CompanionWorkspace) {
        selectedWorkspace = workspace
    }

    func open(conversation: CompanionConversation) async {
        guard let workspace = selectedWorkspace else { return }
        receiveTask?.cancel()
        transcript = []
        pendingApproval = nil
        selectedConversation = conversation
        activeTurn = false
        do {
            let snapshotFrame = try await client.request(CommandFrame(
                requestId: UUID().uuidString,
                command: .getConversation,
                payload: ["conversationId": .string(conversation.id)],
            ))
            guard case let .event(snapshotEvent) = snapshotFrame,
                  let result = snapshotEvent.payload["result"]?.objectValue else {
                try consumeFailure(snapshotFrame)
                return
            }
            transcript = transcriptEntries(from: result["render"])
            let sequence = UInt64(result["sequence"]?.numberValue ?? 0)
            _ = try await client.request(CommandFrame(
                requestId: UUID().uuidString,
                command: .subscribe,
                payload: ["cursors": .array([.object(["conversationId": .string(conversation.id), "sequence": .number(Double(sequence))])])],
            ))
            selectedWorkspace = workspace
            beginReceivingEvents()
        } catch {
            errorMessage = "Unable to open this conversation."
        }
    }

    func startTurn(message: String, assistantId: String, modelId: String) async {
        guard let workspace = selectedWorkspace, let conversation = selectedConversation,
              !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !activeTurn else { return }
        activeTurn = true
        append(TranscriptEntry(id: UUID().uuidString, role: .user, text: message))
        do {
            try await client.send(command: CommandFrame(
                requestId: UUID().uuidString,
                command: .startTurn,
                payload: [
                    "workspaceId": .string(workspace.id),
                    "conversationId": .string(conversation.id),
                    "message": .string(message),
                    "assistantId": .string(assistantId),
                    "modelId": .string(modelId),
                ],
            ))
        } catch {
            activeTurn = false
            errorMessage = "Unable to send the message."
        }
    }

    func cancelTurn() async {
        guard let conversation = selectedConversation else { return }
        do {
            try await client.send(command: CommandFrame(
                requestId: UUID().uuidString,
                command: .cancel,
                payload: ["conversationId": .string(conversation.id)],
            ))
        } catch {
            errorMessage = "Unable to cancel this turn."
        }
    }

    func answer(approval: AgentApproval, approved: Bool) async {
        guard let conversation = selectedConversation else { return }
        pendingApproval = nil
        do {
            try await client.send(command: CommandFrame(
                requestId: UUID().uuidString,
                command: .approval,
                payload: [
                    "conversationId": .string(conversation.id),
                    "approvalId": .string(approval.id),
                    "approved": .boolean(approved),
                ],
            ))
        } catch {
            errorMessage = "Unable to submit the approval."
        }
    }

    private func beginReceivingEvents() {
        receiveTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                do {
                    let frame = try await self.client.nextFrame()
                    await self.handle(frame)
                } catch {
                    if !Task.isCancelled {
                        await self.markDisconnected()
                    }
                    return
                }
            }
        }
    }

    private func handle(_ frame: RemoteInboundFrame) {
        switch frame {
        case let .event(event):
            switch event.event {
            case .messageDelta:
                if let text = text(from: event.payload) {
                    append(TranscriptEntry(id: "\(event.conversationId)-\(event.sequence)", role: .assistant, text: text))
                }
            case .approvalRequested:
                guard let approvalId = event.payload["approvalId"]?.stringValue else { return }
                pendingApproval = AgentApproval(
                    id: approvalId,
                    actionType: event.payload["actionType"]?.stringValue ?? "agent_action",
                    title: event.payload["title"]?.stringValue ?? "Agent approval required",
                    details: event.payload["details"]?.objectValue ?? [:],
                )
            case .turnFinished, .turnFailed:
                activeTurn = false
            default:
                break
            }
        case let .failure(_, error):
            activeTurn = false
            errorMessage = error.message
        default:
            break
        }
    }

    private func markDisconnected() {
        activeTurn = false
        errorMessage = "Disconnected from UOS AI."
    }

    private func consumeFailure(_ frame: RemoteInboundFrame) throws {
        if case let .failure(_, error) = frame { throw error }
        throw CompanionWebSocketError.unexpectedFrame
    }

    private func append(_ entry: TranscriptEntry) {
        guard !transcript.contains(where: { $0.id == entry.id }) else { return }
        transcript.append(entry)
    }
}

private func parseWorkspace(_ value: JSONValue) -> CompanionWorkspace? {
    guard let object = value.objectValue,
          let id = object["value"]?.stringValue else { return nil }
    let conversations = object["conversations"]?.arrayValue?.compactMap { value -> CompanionConversation? in
        guard let item = value.objectValue, let id = item["id"]?.stringValue else { return nil }
        return CompanionConversation(id: id, title: item["title"]?.stringValue ?? "Conversation")
    } ?? []
    return CompanionWorkspace(id: id, label: object["label"]?.stringValue ?? id, conversations: conversations)
}

private func transcriptEntries(from render: JSONValue?) -> [TranscriptEntry] {
    guard let messages = render?.objectValue?["messages"]?.objectValue else { return [] }
    return messages.compactMap { id, value in
        guard let message = value.objectValue,
              let renders = message["render_message"]?.arrayValue,
              let text = renders.compactMap({ $0.objectValue?["data"]?.objectValue?["content"]?.stringValue }).joined(separator: "\n").nilIfEmpty else {
            return nil
        }
        let role = message["role"]?.numberValue == 1 ? TranscriptEntry.Role.user : .assistant
        return TranscriptEntry(id: id, role: role, text: text)
    }
}

private func text(from payload: [String: JSONValue]) -> String? {
    payload["data"]?.objectValue?["content"]?.stringValue ?? payload["content"]?.stringValue
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
