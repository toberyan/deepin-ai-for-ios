import Foundation

struct PairingURI: Equatable {
    let host: String
    let port: UInt16
    let pairingSecret: String
    let expiresAtMs: Int64
    let hostDisplayName: String

    init(url: URL, now: Date = Date()) throws {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == "uos-ai",
              components.host == "pair" else {
            throw PairingURIError.invalidScheme
        }

        let allowedKeys: Set<String> = ["v", "host", "port", "pairingSecret", "expiresAtMs", "hostDisplayName"]
        let queryItems = components.queryItems ?? []
        guard queryItems.allSatisfy({ allowedKeys.contains($0.name) }) else {
            throw PairingURIError.unexpectedParameter
        }
        var values: [String: String] = [:]
        for item in queryItems {
            guard let value = item.value, values[item.name] == nil else {
                throw PairingURIError.missingOrInvalidParameter
            }
            values[item.name] = value
        }
        guard values["v"] == "1",
              let host = values["host"], !host.isEmpty,
              let portString = values["port"], let port = UInt16(portString), port > 0,
              let pairingSecret = values["pairingSecret"], !pairingSecret.isEmpty,
              let expiryString = values["expiresAtMs"], let expiry = Int64(expiryString),
              let displayName = values["hostDisplayName"], !displayName.isEmpty else {
            throw PairingURIError.missingOrInvalidParameter
        }
        guard expiry > Int64(now.timeIntervalSince1970 * 1_000) else {
            throw PairingURIError.expired
        }

        self.host = host
        self.port = port
        self.pairingSecret = pairingSecret
        expiresAtMs = expiry
        hostDisplayName = displayName
    }

    var webSocketURL: URL? {
        let hostComponent = host.contains(":") ? "[\(host)]" : host
        return URL(string: "ws://\(hostComponent):\(port)")
    }
}

enum PairingURIError: Error, Equatable {
    case invalidScheme
    case unexpectedParameter
    case missingOrInvalidParameter
    case expired
}
