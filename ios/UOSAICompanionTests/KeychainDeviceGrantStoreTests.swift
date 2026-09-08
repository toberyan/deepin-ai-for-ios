import XCTest
@testable import UOSAICompanion

final class KeychainDeviceGrantStoreTests: XCTestCase {
    func testProtocolBackedStoreRoundTripsGrant() throws {
        let store = MemoryGrantStore()
        let grant = DeviceGrant(deviceId: "ios-1", token: "device-token", host: "100.64.0.2", port: 45980, hostDisplayName: "UOS-AI")

        try store.save(grant)

        XCTAssertEqual(try store.load(), grant)
        try store.remove()
        XCTAssertNil(try store.load())
    }
}

private final class MemoryGrantStore: DeviceGrantStoring {
    private var grant: DeviceGrant?

    func load() throws -> DeviceGrant? { grant }
    func save(_ grant: DeviceGrant) throws { self.grant = grant }
    func remove() throws { grant = nil }
}
