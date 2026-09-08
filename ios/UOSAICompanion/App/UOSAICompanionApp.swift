import SwiftUI

@main
struct UOSAICompanionApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                if model.pairedHostName == nil {
                    PairingView(model: model)
                } else {
                    WorkspaceListView(model: model.conversations)
                }
            }
            .task { await model.reconnect() }
        }
    }
}
