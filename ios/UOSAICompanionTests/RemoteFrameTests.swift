import XCTest
@testable import UOSAICompanion

final class RemoteFrameTests: XCTestCase {
    func testEncodesStartTurnUsingExactWireName() throws {
        let frame = CommandFrame(
            requestId: "request-1",
            command: .startTurn,
            payload: ["conversationId": .string("conversation-1")],
        )

        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(frame)) as? [String: Any]

        XCTAssertEqual(object?["kind"] as? String, "command")
        XCTAssertEqual(object?["command"] as? String, "start_turn")
        XCTAssertEqual((object?["protocol"] as? [String: Int])?["major"], 1)
    }

    func testPreservesUnknownEvents() throws {
        let data = Data("""
        {"kind":"event","protocol":{"major":1,"minor":0},"conversationId":"c1","sequence":4,"event":"future_event","payload":{}}
        """.utf8)

        let frame = try RemoteInboundFrame.decode(data: data)

        XCTAssertEqual(frame, .event(EventFrame(conversationId: "c1", sequence: 4, event: .unknown("future_event"), payload: [:])))
    }

    func testRejectsIncompatibleProtocol() {
        let data = Data("""
        {"kind":"event","protocol":{"major":2,"minor":0},"conversationId":"c1","sequence":4,"event":"snapshot","payload":{}}
        """.utf8)

        XCTAssertThrowsError(try RemoteInboundFrame.decode(data: data)) { error in
            XCTAssertEqual(error as? RemoteFrameError, .incompatibleProtocol(supportedMajor: 1, receivedMajor: 2))
        }
    }
}
