import XCTest
@testable import Panel

final class AdminRolesTests: XCTestCase {
    func testBanLimitDescriptionsHaveExplicitUnlimitedAndNoBanStates() {
        XCTAssertEqual(SuiteRoleText.limit("-1"), "Keine Banns")
        XCTAssertEqual(SuiteRoleText.limit("0"), "Unbegrenzte Banndauer")
        XCTAssertEqual(SuiteRoleText.limit("259200"), "Banns bis maximal 3 Tage")
        XCTAssertEqual(SuiteRoleText.limit("3600"), "Banns bis maximal 3600 Sekunden")
    }
}
