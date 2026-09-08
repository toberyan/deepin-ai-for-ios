import SwiftUI

struct ConversationView: View {
    @ObservedObject var model: ConversationViewModel
    let workspace: CompanionWorkspace
    let conversation: CompanionConversation
    @State private var draft = ""
    @State private var assistantId = "uos-claw"
    @State private var modelId = "deepseek-chat"

    var body: some View {
        VStack(spacing: 0) {
            if let error = model.errorMessage {
                Text(error).foregroundStyle(.red).padding(.horizontal)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(model.transcript) { entry in
                        HStack {
                            if entry.role == .assistant { Spacer(minLength: 36) }
                            Text(entry.text)
                                .padding(10)
                                .background(entry.role == .user ? Color.accentColor.opacity(0.16) : Color.secondary.opacity(0.12))
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                            if entry.role != .assistant { Spacer(minLength: 36) }
                        }
                    }
                }
                .padding()
            }
            Divider()
            VStack(spacing: 8) {
                HStack {
                    TextField("Assistant ID", text: $assistantId)
                    TextField("Model ID", text: $modelId)
                }
                HStack(alignment: .bottom) {
                    TextField("Continue this conversation", text: $draft, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(1...4)
                    if model.activeTurn {
                        Button("Cancel") { Task { await model.cancelTurn() } }
                    } else {
                        Button("Send") {
                            let message = draft
                            draft = ""
                            Task { await model.startTurn(message: message, assistantId: assistantId, modelId: modelId) }
                        }
                        .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .padding()
        }
        .navigationTitle(conversation.title)
        .task {
            model.select(workspace: workspace)
            await model.open(conversation: conversation)
        }
        .sheet(item: $model.pendingApproval) { approval in
            ApprovalSheet(approval: approval) { allowed in
                Task { await model.answer(approval: approval, approved: allowed) }
            }
            .interactiveDismissDisabled()
        }
    }
}
