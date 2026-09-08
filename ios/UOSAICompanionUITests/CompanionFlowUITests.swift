import XCTest

final class CompanionFlowUITests: XCTestCase {
    func testLaunchesAtPairing() {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.navigationBars["UOS AI"].waitForExistence(timeout: 3))
    }
}
