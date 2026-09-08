import Foundation

enum CompanionConnectionState: String, Equatable, Sendable {
    case disconnected
    case connecting
    case authenticating
    case subscribed
    case reconnecting
}

enum CompanionWebSocketError: Error, Equatable {
    case invalidHost
    case pairingRejected(RemoteError)
    case authenticationRejected(RemoteError)
    case unexpectedFrame
    case nonTextFrame
}

actor CompanionWebSocket {
    private let session: URLSession
    private let grantStore: any DeviceGrantStoring
    private var task: URLSessionWebSocketTask?
    private var connectionState = CompanionConnectionState.disconnected

    init(session: URLSession = .shared, grantStore: any DeviceGrantStoring = KeychainDeviceGrantStore()) {
        self.session = session
        self.grantStore = grantStore
    }

    func state() -> CompanionConnectionState {
        connectionState
    }

    func pair(invitation: PairingURI, deviceName: String) async throws -> DeviceGrant {
        guard let url = invitation.webSocketURL else { throw CompanionWebSocketError.invalidHost }
        try await open(url: url)
        try await send(PairFrame(payload: .init(pairingSecret: invitation.pairingSecret, deviceName: deviceName)))

        let pairingResponse = try await receive()
        guard case let .pairingGranted(pairingGrant) = pairingResponse else {
            if case let .failure(_, error) = pairingResponse {
                throw CompanionWebSocketError.pairingRejected(error)
            }
            throw CompanionWebSocketError.unexpectedFrame
        }

        let grant = DeviceGrant(
            deviceId: pairingGrant.deviceId,
            token: pairingGrant.token,
            host: invitation.host,
            port: invitation.port,
            hostDisplayName: invitation.hostDisplayName,
        )
        try grantStore.save(grant)
        try await authenticate(grant)
        return grant
    }

    func connectSavedGrant() async throws -> DeviceGrant? {
        guard let grant = try grantStore.load() else { return nil }
        guard let url = webSocketURL(for: grant) else { throw CompanionWebSocketError.invalidHost }
        try await open(url: url)
        try await authenticate(grant)
        return grant
    }

    func subscribe(cursors: [[String: JSONValue]], requestId: String) async throws {
        try await send(CommandFrame(
            requestId: requestId,
            command: .subscribe,
            payload: ["cursors": .array(cursors.map { .object($0) })],
        ))
        connectionState = .subscribed
    }

    func send(command: CommandFrame) async throws {
        try await send(command)
    }

    func request(_ command: CommandFrame) async throws -> RemoteInboundFrame {
        try await send(command)
        while true {
            let frame = try await receive()
            switch frame {
            case let .failure(requestId, _):
                if requestId == command.requestId { return frame }
            case let .event(event):
                if event.event == .commandAck,
                   event.payload["requestId"]?.stringValue == command.requestId {
                    return frame
                }
            default:
                break
            }
        }
    }

    func nextFrame() async throws -> RemoteInboundFrame {
        try await receive()
    }

    func disconnect() {
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        connectionState = .disconnected
    }

    private func open(url: URL) async throws {
        disconnect()
        connectionState = .connecting
        let nextTask = session.webSocketTask(with: url)
        task = nextTask
        nextTask.resume()
    }

    private func authenticate(_ grant: DeviceGrant) async throws {
        connectionState = .authenticating
        try await send(AuthenticateFrame(deviceId: grant.deviceId, token: grant.token))
        let response = try await receive()
        switch response {
        case .authenticated:
            connectionState = .subscribed
        case let .failure(_, error):
            disconnect()
            throw CompanionWebSocketError.authenticationRejected(error)
        default:
            disconnect()
            throw CompanionWebSocketError.unexpectedFrame
        }
    }

    private func send<T: Encodable>(_ frame: T) async throws {
        guard let task else { throw URLError(.notConnectedToInternet) }
        let data = try JSONEncoder().encode(frame)
        guard let text = String(data: data, encoding: .utf8) else { throw CompanionWebSocketError.nonTextFrame }
        try await task.send(.string(text))
    }

    private func receive() async throws -> RemoteInboundFrame {
        guard let task else { throw URLError(.notConnectedToInternet) }
        let message = try await task.receive()
        let data: Data
        switch message {
        case let .string(text):
            data = Data(text.utf8)
        case let .data(value):
            data = value
        @unknown default:
            throw CompanionWebSocketError.nonTextFrame
        }
        return try RemoteInboundFrame.decode(data: data)
    }

    private func webSocketURL(for grant: DeviceGrant) -> URL? {
        let host = grant.host.contains(":") ? "[\(grant.host)]" : grant.host
        return URL(string: "wss://\(host):\(grant.port)")
    }
}
