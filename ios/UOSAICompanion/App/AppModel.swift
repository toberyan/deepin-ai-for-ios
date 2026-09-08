import Foundation
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var connectionState = CompanionConnectionState.disconnected
    @Published private(set) var pairedHostName: String?
    @Published var errorMessage: String?

    private let client: CompanionWebSocket
    let conversations: ConversationViewModel

    init(client: CompanionWebSocket = CompanionWebSocket()) {
        self.client = client
        conversations = ConversationViewModel(client: client)
    }

    func pair(pastedInvitation: String, deviceName: String) async {
        do {
            errorMessage = nil
            let invitation = try PairingURI(url: try url(from: pastedInvitation))
            connectionState = .connecting
            let grant = try await client.pair(invitation: invitation, deviceName: deviceName)
            pairedHostName = grant.hostDisplayName
            connectionState = await client.state()
            await conversations.loadWorkspaces()
        } catch {
            connectionState = .disconnected
            errorMessage = "Pairing failed. Check the invitation and your Tailscale connection."
        }
    }

    func reconnect() async {
        do {
            errorMessage = nil
            connectionState = .connecting
            if let grant = try await client.connectSavedGrant() {
                pairedHostName = grant.hostDisplayName
                connectionState = await client.state()
                await conversations.loadWorkspaces()
            } else {
                connectionState = .disconnected
            }
        } catch {
            connectionState = .reconnecting
            errorMessage = "Unable to reconnect to UOS AI."
        }
    }

    private func url(from raw: String) throws -> URL {
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw URLError(.badURL)
        }
        return url
    }
}
