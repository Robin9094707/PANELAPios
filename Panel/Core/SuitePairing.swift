import Foundation
import Network
import Security
import CryptoKit
import SwiftUI

// MARK: - 5.11.0 simpler pairing

/// What a short look at an address revealed. Nothing secret is sent while probing.
struct SuiteProbeResult: Sendable, Equatable {
    /// TLS handshake completed.
    var reachable = false
    /// SHA-256 of the server's leaf certificate (lowercase hex), empty when no certificate arrived.
    var fingerprint = ""
    /// The certificate chain is publicly valid for this host name (e.g. Let's Encrypt behind a domain).
    var publiclyTrusted = false
    /// HTTP status of GET /v1/ping, 0 when no HTTP answer arrived.
    var status = 0
    /// The Admin Suite itself answered /v1/ping (Admin Suite 10.18.0 and newer).
    var suite = false
}

/// The server presents a certificate that is neither publicly valid nor the entered fingerprint.
/// The user compares it with /sas app fingerprint and confirms it once.
struct SuiteCertificateOffer: Error, Identifiable, Equatable, LocalizedError {
    let fingerprint: String
    let previous: String
    var id: String { fingerprint }
    var errorDescription: String? { "Unbekanntes Zertifikat. Bitte mit /sas app fingerprint vergleichen und bestätigen." }
    /// Groups of eight characters, easier to compare with the console.
    var grouped: String { stride(from: 0, to: fingerprint.count, by: 8).map { offset -> String in let start = fingerprint.index(fingerprint.startIndex, offsetBy: offset); return String(fingerprint[start..<(fingerprint.index(start, offsetBy: 8, limitedBy: fingerprint.endIndex) ?? fingerprint.endIndex)]) }.joined(separator: " ") }
}

enum SuitePairing {
    /// Decides which fingerprint a new connection should use, or explains in plain words why it cannot connect.
    /// - Entered fingerprint matches: keep it.
    /// - Publicly valid certificate (domain, proxy): no fingerprint needed.
    /// - Anything else: throws `SuiteCertificateOffer` so the user can confirm the certificate once.
    static func pin(for url: URL, entered: String, timeout: TimeInterval = 10) async throws -> String {
        let entered = try SuiteAPI.pin(entered)
        let result = await SuiteProbe.run(url, timeout: timeout)
        try check(result, url: url)
        return try decide(result, entered: entered)
    }
    static func check(_ result: SuiteProbeResult, url: URL) throws {
        let host = url.host ?? "", port = url.port ?? 443
        guard result.reachable else { throw PanelError(message: unreachable(host: host, port: port, explicitPort: url.port != nil)) }
        if !result.suite, [502, 503, 504, 521, 522, 523, 525, 526].contains(result.status) {
            throw PanelError(message: "Unter \(host) antwortet ein Proxy mit Fehler \(result.status): Er erreicht die Admin Suite dahinter nicht. Im Proxy als Ziel https://<Server-IP>:<API-Port> eintragen (/sas app status zeigt Port und Adresse) – oder hier direkt https://<Server-IP>:<API-Port> verwenden.")
        }
    }
    static func decide(_ result: SuiteProbeResult, entered: String) throws -> String {
        if !entered.isEmpty, result.fingerprint == entered { return entered }
        if result.publiclyTrusted { return "" }
        guard result.fingerprint.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw PanelError(message: "Der Server hat kein prüfbares Zertifikat gesendet.") }
        throw SuiteCertificateOffer(fingerprint: result.fingerprint, previous: entered)
    }
    static func unreachable(host: String, port: Int, explicitPort: Bool) -> String {
        if explicitPort {
            return "Unter \(host):\(port) antwortet nichts. In der Serverkonsole /sas app status eingeben: Port und Adresse müssen übereinstimmen, und Port \(port) muss beim Hoster diesem Server zugewiesen sein (Netzwerk / Ports). Die IP ist die des Servers aus dem Hoster-Panel."
        }
        return "Unter \(host) antwortet nichts. Meist fehlt der Port: https://<Server-IP>:<API-Port> eintragen – /sas app status in der Serverkonsole zeigt beides."
    }
}

enum SuiteProbe {
    static func run(_ url: URL, timeout: TimeInterval = 10) async -> SuiteProbeResult {
        await withCheckedContinuation { continuation in
            SuiteProbeConnection(url: url, timeout: timeout) { continuation.resume(returning: $0) }.start()
        }
    }
    /// Status code from the first line of an HTTP answer, nil until the line is complete.
    static func status(_ bytes: Data) -> Int? {
        guard let end = bytes.range(of: Data([13, 10])), let line = String(data: bytes[..<end.lowerBound], encoding: .isoLatin1) else { return nil }
        let parts = line.split(separator: " ")
        guard parts.count >= 2, parts[0].hasPrefix("HTTP/1."), let code = Int(parts[1]) else { return 0 }
        return code
    }
}

/// One TLS connection that records the certificate, checks it against the public trust store and asks /v1/ping.
/// Any certificate may answer because only a fixed, anonymous request is sent.
private final class SuiteProbeConnection: @unchecked Sendable {
    private let url: URL
    private let timeout: TimeInterval
    private var completion: ((SuiteProbeResult) -> Void)?
    private var result = SuiteProbeResult()
    private let queue = DispatchQueue(label: "eu.simplexsmp.panel.suite-probe")
    private var connection: NWConnection?
    private var buffer = Data()
    private var keepAlive: SuiteProbeConnection?
    init(url: URL, timeout: TimeInterval, completion: @escaping (SuiteProbeResult) -> Void) { self.url = url; self.timeout = timeout; self.completion = completion }
    func start() {
        queue.async {
            self.keepAlive = self
            guard self.url.scheme == "https", let rawHost = self.url.host, let port = NWEndpoint.Port(rawValue: UInt16(exactly: self.url.port ?? 443) ?? 0), port.rawValue != 0 else { self.finish(); return }
            let host = rawHost.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
            sec_protocol_options_add_tls_application_protocol(tls.securityProtocolOptions, "http/1.1")
            sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { [weak self] _, trust, done in
                guard let self else { done(false); return }
                let value = sec_trust_copy_ref(trust).takeRetainedValue()
                if let leaf = (SecTrustCopyCertificateChain(value) as? [SecCertificate])?.first {
                    self.result.fingerprint = SHA256.hash(data: SecCertificateCopyData(leaf) as Data).map { String(format: "%02x", $0) }.joined()
                }
                SecTrustSetPolicies(value, SecPolicyCreateSSL(true, host as CFString))
                self.result.publiclyTrusted = SecTrustEvaluateWithError(value, nil)
                done(true)
            }, self.queue)
            let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
            self.connection = connection
            connection.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready: self.result.reachable = true; self.send(host: rawHost)
                case .failed(_), .waiting(_): self.finish()
                default: break
                }
            }
            self.queue.asyncAfter(deadline: .now() + self.timeout) { [weak self] in self?.finish() }
            connection.start(queue: self.queue)
        }
    }
    private func send(host: String) {
        let authority = (host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host) + (url.port.map { ":\($0)" } ?? "")
        let base = url.path.hasSuffix("/") ? String(url.path.dropLast()) : url.path
        let request = "GET \(base)/v1/ping HTTP/1.1\r\nHost: \(authority)\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
        connection?.send(content: Data(request.utf8), completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            if error != nil { self.finish() } else { self.receive() }
        })
    }
    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, complete, error in
            guard let self, self.completion != nil else { return }
            if let data { self.buffer.append(data) }
            if let code = SuiteProbe.status(self.buffer) { self.result.status = code }
            if self.buffer.count > 65536 || complete || error != nil {
                self.result.suite = self.result.status == 200 && String(data: self.buffer, encoding: .utf8)?.contains("simplex-admin-suite") == true
                self.finish()
            } else { self.receive() }
        }
    }
    private func finish() {
        guard let completion else { return }
        self.completion = nil
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        let value = result
        keepAlive = nil
        completion(value)
    }
}

// MARK: - Confirm a new certificate

extension View {
    /// Shows a found certificate and lets the user trust it after comparing it with /sas app fingerprint.
    func suiteCertificatePrompt(_ offer: Binding<SuiteCertificateOffer?>, accept: @escaping @MainActor (String) -> Void) -> some View {
        sheet(item: offer) { item in SuiteCertificateSheet(offer: item) { accept(item.fingerprint) } }
    }
}

struct SuiteCertificateSheet: View {
    let offer: SuiteCertificateOffer
    var accept: @MainActor () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var compared: Bool?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: "checkmark.shield").font(.system(size: 40)).foregroundStyle(Color.panelAccent)
                        Text(offer.previous.isEmpty ? "Zertifikat bestätigen" : "Neues Zertifikat gefunden").font(.title2.bold())
                        Text(offer.previous.isEmpty ? "Der Server nutzt ein eigenes Zertifikat. Bestätige es einmal, danach verbindet sich die App nur noch mit genau diesem Server." : "Der Server meldet sich mit einem anderen Zertifikat als gespeichert – normal nach einem Serverumzug oder Neuinstallation.").foregroundStyle(.secondary)
                    }.padding(.vertical, 6)
                }
                Section {
                    Text(offer.grouped).font(.callout.monospaced()).textSelection(.enabled)
                    if let compared { Label(compared ? "Stimmt mit der Zwischenablage überein" : "Weicht von der Zwischenablage ab", systemImage: compared ? "checkmark.circle.fill" : "xmark.octagon.fill").foregroundStyle(compared ? .green : .red) }
                    Button("Mit Zwischenablage vergleichen", systemImage: "doc.on.clipboard") {
                        let pasted = (UIPasteboard.general.string ?? "").lowercased().replacingOccurrences(of: "sha-256:", with: "").replacingOccurrences(of: ":", with: "").filter(\.isHexDigit)
                        compared = pasted == offer.fingerprint
                    }
                } header: { Text("Fingerabdruck des Servers") } footer: { Text("Zum Vergleichen in der Serverkonsole /sas app fingerprint eingeben. Die ersten und letzten Zeichen genügen meist. Stimmt er nicht, nicht bestätigen.") }
                Section {
                    Button { accept(); dismiss() } label: { HStack { Spacer(); Label("Vertrauen & verbinden", systemImage: "checkmark.shield.fill").bold(); Spacer() } }.disabled(compared == false)
                }
            }.panelScreen().navigationTitle("Zertifikat").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
}
