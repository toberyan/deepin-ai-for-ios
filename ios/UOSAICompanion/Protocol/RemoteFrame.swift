import Foundation

enum RemoteProtocol {
    static let major = 1
    static let minor = 0
}

struct ProtocolVersion: Codable, Equatable {
    let major: Int
    let minor: Int

    static let current = ProtocolVersion(major: RemoteProtocol.major, minor: RemoteProtocol.minor)
}

enum RemoteCommandName: String, Codable, CaseIterable {
    case subscribe
    case listWorkspaces = "list_workspaces"
    case getConversation = "get_conversation"
    case startTurn = "start_turn"
    case cancel
    case approval
}

struct CommandFrame: Encodable, Equatable {
    let kind = "command"
    let protocolVersion = ProtocolVersion.current
    let requestId: String
    let command: RemoteCommandName
    let payload: [String: JSONValue]

    enum CodingKeys: String, CodingKey {
        case kind, requestId, command, payload
        case protocolVersion = "protocol"
    }
}

struct PairFrame: Encodable, Equatable {
    let kind = "pair"
    let protocolVersion = ProtocolVersion.current
    let payload: Payload

    struct Payload: Codable, Equatable {
        let pairingSecret: String
        let deviceName: String
    }

    enum CodingKeys: String, CodingKey {
        case kind, payload
        case protocolVersion = "protocol"
    }
}

struct AuthenticateFrame: Encodable, Equatable {
    let kind = "authenticate"
    let deviceId: String
    let token: String
}

struct PairingGrant: Codable, Equatable {
    let deviceId: String
    let token: String
    let displayName: String
    let allowedWorkspaceIds: [String]
}

struct RemoteError: Codable, Equatable, Error {
    let code: String
    let message: String
    let details: [String: JSONValue]
}

enum RemoteEventName: Equatable {
    case snapshot
    case messageDelta
    case stateDelta
    case approvalRequested
    case turnFinished
    case turnFailed
    case commandAck
    case unknown(String)

    init(wireValue: String) {
        switch wireValue {
        case "snapshot": self = .snapshot
        case "message_delta": self = .messageDelta
        case "state_delta": self = .stateDelta
        case "agent_approval_requested": self = .approvalRequested
        case "turn_finished": self = .turnFinished
        case "turn_failed": self = .turnFailed
        case "command_ack": self = .commandAck
        default: self = .unknown(wireValue)
        }
    }
}

struct EventFrame: Equatable {
    let conversationId: String
    let sequence: UInt64
    let event: RemoteEventName
    let payload: [String: JSONValue]
}

enum RemoteInboundFrame: Equatable {
    case pairingGranted(PairingGrant)
    case authenticated(deviceId: String, displayName: String)
    case event(EventFrame)
    case failure(requestId: String?, error: RemoteError)
}

enum RemoteFrameError: Error, Equatable {
    case invalidFrame
    case incompatibleProtocol(supportedMajor: Int, receivedMajor: Int)
    case unexpectedKind(String)
}

extension RemoteInboundFrame {
    static func decode(data: Data, decoder: JSONDecoder = JSONDecoder()) throws -> RemoteInboundFrame {
        let envelope = try decoder.decode(InboundEnvelope.self, from: data)
        if let version = envelope.protocolVersion, version.major != RemoteProtocol.major {
            throw RemoteFrameError.incompatibleProtocol(
                supportedMajor: RemoteProtocol.major,
                receivedMajor: version.major,
            )
        }

        switch envelope.kind {
        case "pairing_granted":
            guard let payload = envelope.payload,
                  let grant = try? payload.decode(PairingGrant.self) else {
                throw RemoteFrameError.invalidFrame
            }
            return .pairingGranted(grant)
        case "authenticated":
            guard let payload = envelope.payload else { throw RemoteFrameError.invalidFrame }
            let authenticated = try payload.decode(AuthenticatedPayload.self)
            return .authenticated(deviceId: authenticated.deviceId, displayName: authenticated.displayName)
        case "event":
            guard let payload = envelope.payload,
                  let conversationId = envelope.conversationId,
                  let sequence = envelope.sequence,
                  let wireEvent = envelope.event else {
                throw RemoteFrameError.invalidFrame
            }
            return .event(EventFrame(
                conversationId: conversationId,
                sequence: sequence,
                event: RemoteEventName(wireValue: wireEvent),
                payload: try payload.decode([String: JSONValue].self),
            ))
        case "error":
            guard let error = envelope.error else { throw RemoteFrameError.invalidFrame }
            return .failure(requestId: envelope.requestId, error: error)
        default:
            throw RemoteFrameError.unexpectedKind(envelope.kind)
        }
    }

    private struct InboundEnvelope: Decodable {
        let kind: String
        let protocolVersion: ProtocolVersion?
        let requestId: String?
        let conversationId: String?
        let sequence: UInt64?
        let event: String?
        let payload: DeferredPayload?
        let error: RemoteError?

        enum CodingKeys: String, CodingKey {
            case kind, requestId, conversationId, sequence, event, payload, error
            case protocolVersion = "protocol"
        }
    }

    private struct AuthenticatedPayload: Decodable {
        let deviceId: String
        let displayName: String
    }

    private struct DeferredPayload: Decodable {
        let value: Data

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            let object = try container.decode(JSONValue.self)
            value = try JSONEncoder().encode(object)
        }

        func decode<T: Decodable>(_ type: T.Type) throws -> T {
            try JSONDecoder().decode(T.self, from: value)
        }
    }
}

enum JSONValue: Codable, Equatable {
    case string(String)
    case number(Double)
    case boolean(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let bool = try? container.decode(Bool.self) { self = .boolean(bool) }
        else if let number = try? container.decode(Double.self) { self = .number(number) }
        else if let string = try? container.decode(String.self) { self = .string(string) }
        else if let array = try? container.decode([JSONValue].self) { self = .array(array) }
        else { self = .object(try container.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case let .string(value): try container.encode(value)
        case let .number(value): try container.encode(value)
        case let .boolean(value): try container.encode(value)
        case let .object(value): try container.encode(value)
        case let .array(value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }
}

extension JSONValue {
    var stringValue: String? {
        if case let .string(value) = self { return value }
        return nil
    }

    var numberValue: Double? {
        if case let .number(value) = self { return value }
        return nil
    }

    var booleanValue: Bool? {
        if case let .boolean(value) = self { return value }
        return nil
    }

    var objectValue: [String: JSONValue]? {
        if case let .object(value) = self { return value }
        return nil
    }

    var arrayValue: [JSONValue]? {
        if case let .array(value) = self { return value }
        return nil
    }
}
