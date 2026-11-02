import XCTest
@testable import Panel

/// Focused 4.1.0 checks: multi-step sign-in, passkey availability, device header and release notes.
@MainActor final class Panel410Tests: XCTestCase {
    func testLoginChallengeParsing() {
        let mfa = SuiteLoginChallenge.from(.object(["mfa": .bool(true), "challenge": .string("abc"), "username": .string("robin"), "methods": .array([.string("totp"), .string("recovery")])]))
        XCTAssertEqual(mfa?.kind, .secondFactor)
        XCTAssertEqual(mfa?.methods, ["totp", "recovery"])
        XCTAssertEqual(mfa?.username, "robin")
        let reset = SuiteLoginChallenge.from(.object(["passwordChange": .bool(true), "challenge": .string("xyz"), "message": .string("Neu setzen")]))
        XCTAssertEqual(reset?.kind, .newPassword)
        XCTAssertNil(SuiteLoginChallenge.from(.object(["token": .string("sasu_abc")])), "a token is not a challenge")
        XCTAssertNil(SuiteLoginChallenge.from(.object(["mfa": .bool(true)])), "a challenge id is required")
    }
    func testPasskeysOnlyOnDomainsWithPublicCertificates() {
        XCTAssertTrue(SuitePasskeySignInButton.possible(endpoint: "https://admin.example.eu", fingerprint: ""))
        XCTAssertFalse(SuitePasskeySignInButton.possible(endpoint: "https://192.168.1.20:39093", fingerprint: ""), "IP addresses cannot hold passkeys")
        XCTAssertFalse(SuitePasskeySignInButton.possible(endpoint: "https://admin.example.eu", fingerprint: String(repeating: "a", count: 64)), "self-signed pins are not trusted by Safari")
    }
    func testDeviceHeaderIsEncodedSafely() {
        let header = SuiteDevice.header
        XCTAssertTrue(header.hasPrefix("model="))
        XCTAssertTrue(header.contains(";os=") && header.contains(";app="))
        XCTAssertFalse(header.contains(" "), "values are percent-encoded")
        XCTAssertFalse(header.contains("\n"))
    }
    func testReleaseNotesFor410() {
        XCTAssertEqual(WhatsNew.release(for: "4.1.0")?.version, "4.1.0")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "4.1.0", lastSeen: "4.0.0", existingUser: true))
    }
}
