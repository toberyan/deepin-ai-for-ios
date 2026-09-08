import XCTest
@testable import UOSAICompanion

final class PairingURITests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    func testParsesValidInvitation() throws {
        let url = try XCTUnwrap(URL(string: "uos-ai://pair?v=1&host=100.64.0.2&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI"))

        let invitation = try PairingURI(url: url, now: now)

        XCTAssertEqual(invitation.host, "100.64.0.2")
        XCTAssertEqual(invitation.port, 45980)
        XCTAssertEqual(invitation.webSocketURL?.absoluteString, "ws://100.64.0.2:45980")
    }

    func testRejectsExpiredInvitationAndUnknownParameters() throws {
        let expired = try XCTUnwrap(URL(string: "uos-ai://pair?v=1&host=100.64.0.2&port=45980&pairingSecret=abc&expiresAtMs=1699999999999&hostDisplayName=UOS-AI"))
        XCTAssertThrowsError(try PairingURI(url: expired, now: now)) { error in
            XCTAssertEqual(error as? PairingURIError, .expired)
        }

        let unexpected = try XCTUnwrap(URL(string: "uos-ai://pair?v=1&host=100.64.0.2&port=45980&pairingSecret=abc&expiresAtMs=1700000300000&hostDisplayName=UOS-AI&redirect=https://example.com"))
        XCTAssertThrowsError(try PairingURI(url: unexpected, now: now)) { error in
            XCTAssertEqual(error as? PairingURIError, .unexpectedParameter)
        }
    }
}
