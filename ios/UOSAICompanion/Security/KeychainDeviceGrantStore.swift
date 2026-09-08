import Foundation
import Security

struct DeviceGrant: Codable, Equatable {
    let deviceId: String
    let token: String
    let host: String
    let port: UInt16
    let hostDisplayName: String
}

protocol DeviceGrantStoring {
    func load() throws -> DeviceGrant?
    func save(_ grant: DeviceGrant) throws
    func remove() throws
}

enum DeviceGrantStoreError: Error, Equatable {
    case unexpectedStatus(OSStatus)
    case encodingFailed
    case decodingFailed
}

final class KeychainDeviceGrantStore: DeviceGrantStoring {
    private let service = "org.deepin.uos-ai-companion"
    private let account = "tailscale-device-grant"

    func load() throws -> DeviceGrant? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw DeviceGrantStoreError.unexpectedStatus(status)
        }
        guard let grant = try? JSONDecoder().decode(DeviceGrant.self, from: data) else {
            throw DeviceGrantStoreError.decodingFailed
        }
        return grant
    }

    func save(_ grant: DeviceGrant) throws {
        guard let data = try? JSONEncoder().encode(grant) else {
            throw DeviceGrantStoreError.encodingFailed
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecItemNotFound {
            var insert = query
            attributes.forEach { insert[$0.key] = $0.value }
            let addStatus = SecItemAdd(insert as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw DeviceGrantStoreError.unexpectedStatus(addStatus) }
        } else if updateStatus != errSecSuccess {
            throw DeviceGrantStoreError.unexpectedStatus(updateStatus)
        }
    }

    func remove() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw DeviceGrantStoreError.unexpectedStatus(status)
        }
    }
}
