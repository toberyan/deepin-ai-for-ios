import SwiftUI

struct WorkspaceListView: View {
    @ObservedObject var model: ConversationViewModel

    var body: some View {
        List {
            if let error = model.errorMessage {
                Text(error).foregroundStyle(.red)
            }
            ForEach(model.workspaces) { workspace in
                Section(workspace.label) {
                    if workspace.conversations.isEmpty {
                        Text("No shared conversations.").foregroundStyle(.secondary)
                    }
                    ForEach(workspace.conversations) { conversation in
                        NavigationLink(conversation.title) {
                            ConversationView(model: model, workspace: workspace, conversation: conversation)
                        }
                        .simultaneousGesture(TapGesture().onEnded { model.select(workspace: workspace) })
                    }
                }
            }
        }
        .navigationTitle("Workspaces")
        .task { await model.loadWorkspaces() }
        .refreshable { await model.loadWorkspaces() }
    }
}
