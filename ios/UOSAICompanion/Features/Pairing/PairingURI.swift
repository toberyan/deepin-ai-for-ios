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
              let rawHost = values["host"], !rawHost.isEmpty,
              let portString = values["port"], let port = UInt16(portString), port > 0,
              let pairingSecret = values["pairingSecret"], !pairingSecret.isEmpty,
              let expiryString = values["expiresAtMs"], let expiry = Int64(expiryString),
              let displayName = values["hostDisplayName"], !displayName.isEmpty else {
            throw PairingURIError.missingOrInvalidParameter
        }
        let host = rawHost.lowercased()
        guard expiry > Int64(now.timeIntervalSince1970 * 1_000) else {
            throw PairingURIError.expired
        }
        guard PairingURI.isSafeTailnetHostname(host) else {
            throw PairingURIError.unsafeHost
        }

        self.host = host
        self.port = port
        self.pairingSecret = pairingSecret
        expiresAtMs = expiry
        hostDisplayName = displayName
    }

    var webSocketURL: URL? {
        let hostComponent = host.contains(":") ? "[\(host)]" : host
        return URL(string: "wss://\(hostComponent):\(port)")
    }

    private static func isSafeTailnetHostname(_ host: String) -> Bool {
        guard host.unicodeScalars.allSatisfy({ $0.value <= 0x7f }), host.hasSuffix(".ts.net") else {
            return false
        }
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 3 else { return false }
        return labels.allSatisfy { label in
            guard let first = label.first, let last = label.last,
                  first.isLetter || first.isNumber, last.isLetter || last.isNumber else {
                return false
            }
            return label.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" }
        }
    }
}

enum PairingURIError: Error, Equatable {
    case invalidScheme
    case unexpectedParameter
    case missingOrInvalidParameter
    case expired
    case unsafeHost
}
