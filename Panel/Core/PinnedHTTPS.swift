import Foundation
import Network
import Security
import CryptoKit

/// Explicitly pinned TLS for user-owned servers. Foundation ATS remains enabled elsewhere.
/// Only HTTPS and HTTP/1.1 over TLS 1.2+; no redirect, proxy, cookie or plaintext fallback.
enum PinnedHTTPS {
    static let limit = 8 * 1024 * 1024
    static func load(_ request: URLRequest, pin: String) async throws -> (Data, URLResponse) {
        let transfer = try PinnedHTTPTransfer(request: request, pin: pin)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in transfer.start(continuation) }
        } onCancel: { transfer.cancel() }
    }
    struct Message {
        let status: Int
        let headers: [String: String]
        let data: Data
    }
    static func decode(_ bytes: Data, complete: Bool) throws -> Message? {
        guard bytes.count <= limit + 65536 else { throw PanelError(message: "Suite-Antwort zu groß.") }
        let marker = Data([13, 10, 13, 10])
        guard let separator = bytes.range(of: marker) else {
            if bytes.count > 65536 || complete { throw PanelError(message: "Ungültige HTTPS-Antwort.") }
            return nil
        }
        guard separator.lowerBound <= 65536,
              let header = String(data: bytes[..<separator.lowerBound], encoding: .isoLatin1) else { throw PanelError(message: "Ungültige HTTPS-Kopfzeile.") }
        let lines = header.components(separatedBy: "\r\n")
        let statusLine = (lines.first ?? "").split(separator: " ")
        guard statusLine.count >= 2, ["HTTP/1.1", "HTTP/1.0"].contains(String(statusLine[0])),
              let status = Int(statusLine[1]), (200...599).contains(status) else { throw PanelError(message: "Nicht unterstützte HTTPS-Antwort.") }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { throw PanelError(message: "Ungültige HTTPS-Kopfzeile.") }
            let name = line[..<colon].lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty, name.range(of: "^[a-z0-9!#$%&'*+.^_`|~-]+$", options: .regularExpression) != nil,
                  !["content-length", "transfer-encoding"].contains(name) || headers[name] == nil else { throw PanelError(message: "Mehrdeutige HTTPS-Antwort.") }
            headers[name] = value
        }
        let body = Data(bytes[separator.upperBound...])
        if headers["transfer-encoding"] != nil && headers["content-length"] != nil { throw PanelError(message: "Mehrdeutige HTTPS-Längenangabe.") }
        if let encoding = headers["content-encoding"], encoding.lowercased() != "identity" { throw PanelError(message: "Der Proxy muss unkomprimierte API-Antworten erlauben (Accept-Encoding: identity).") }
        let decoded: Data
        if let transfer = headers["transfer-encoding"] {
            guard transfer.lowercased() == "chunked" else { throw PanelError(message: "Nicht unterstützte HTTPS-Übertragung.") }
            guard let value = try chunks(body, complete: complete) else { return nil }
            decoded = value
        } else if let rawLength = headers["content-length"] {
            guard rawLength.range(of: "^[0-9]+$", options: .regularExpression) != nil, let length = Int(rawLength), length <= limit else { throw PanelError(message: "HTTPS-Antwort überschreitet das Größenlimit.") }
            if body.count < length {
                if complete { throw PanelError(message: "HTTPS-Antwort unvollständig.") }
                return nil
            }
            guard body.count == length else { throw PanelError(message: "HTTPS-Antwort hat eine unerwartete Länge.") }
            decoded = body
        } else {
            guard complete else { return nil }
            guard body.count <= limit else { throw PanelError(message: "Suite-Antwort zu groß.") }
            decoded = body
        }
        return Message(status: status, headers: headers, data: decoded)
    }
    private static func chunks(_ data: Data, complete: Bool) throws -> Data? {
        var index = 0
        var result = Data()
        func incomplete() throws -> Data? {
            if complete { throw PanelError(message: "HTTPS-Chunk unvollständig.") }
            return nil
        }
        while index < data.count {
            guard let end = data.range(of: Data([13, 10]), in: index..<data.count) else { return try incomplete() }
            guard end.lowerBound - index <= 1024,
                  let sizeText = String(data: data[index..<end.lowerBound], encoding: .ascii)?.split(separator: ";", omittingEmptySubsequences: false).first,
                  !sizeText.isEmpty, sizeText.count <= 8, sizeText.allSatisfy(\.isHexDigit),
                  let size = Int(sizeText, radix: 16), size <= limit - result.count else { throw PanelError(message: "Ungültiger HTTPS-Chunk.") }
            index = end.upperBound
            if size == 0 {
                if data.count >= index + 2, data[index] == 13, data[index + 1] == 10 { return result }
                if data.range(of: Data([13, 10, 13, 10]), in: index..<data.count) != nil { return result }
                return try incomplete()
            }
            guard data.count >= index + size + 2 else { return try incomplete() }
            guard data[index + size] == 13, data[index + size + 1] == 10 else { throw PanelError(message: "Ungültige HTTPS-Chunk-Grenze.") }
            result.append(data[index..<index + size])
            index += size + 2
        }
        return try incomplete()
    }
}

private final class PinnedHTTPTransfer: @unchecked Sendable {
    private let request: URLRequest
    private let pin: String
    private let queue = DispatchQueue(label: "eu.simplexsmp.panel.pinned-https")
    private var continuation: CheckedContinuation<(Data, URLResponse), Error>?
    private var connection: NWConnection?
    private var timeout: DispatchWorkItem?
    private var buffer = Data()
    private var cancelled = false
    private var pinRejected = false
    init(request: URLRequest, pin: String) throws {
        guard request.url?.scheme == "https", request.url?.host != nil, ["GET", "POST"].contains(request.httpMethod ?? "GET"), pin.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              request.allHTTPHeaderFields?.values.allSatisfy({ !$0.contains("\r") && !$0.contains("\n") }) ?? true else { throw PanelError(message: "Ungültige gepinnte HTTPS-Anfrage.") }
        self.request = request
        self.pin = pin
    }
    func start(_ continuation: CheckedContinuation<(Data, URLResponse), Error>) {
        queue.async {
            self.continuation = continuation
            if self.cancelled { self.finish(.failure(CancellationError())); return }
            guard let url = self.request.url, let host = url.host,
                  let port = NWEndpoint.Port(rawValue: UInt16(exactly: url.port ?? 443) ?? 0), port.rawValue != 0 else { self.finish(.failure(PanelError(message: "Ungültiger HTTPS-Port."))); return }
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
            sec_protocol_options_add_tls_application_protocol(tls.securityProtocolOptions, "http/1.1")
            sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { [weak self] _, trust, done in
                guard let self else { done(false); return }
                let value = sec_trust_copy_ref(trust).takeRetainedValue()
                guard let certificate = (SecTrustCopyCertificateChain(value) as? [SecCertificate])?.first else { self.pinRejected = true; done(false); return }
                let digest = SHA256.hash(data: SecCertificateCopyData(certificate) as Data).map { String(format: "%02x", $0) }.joined()
                self.pinRejected = digest != self.pin
                done(!self.pinRejected)
            }, self.queue)
            let connection = NWConnection(host: NWEndpoint.Host(host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))), port: port, using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()))
            self.connection = connection
            connection.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready: self.send()
                case .failed(let error): self.finish(.failure(self.pinRejected ? PanelError(message: "Zertifikatsfingerabdruck stimmt nicht überein. Kopplung prüfen; es wird keine Verbindung mit einem anderen Zertifikat zugelassen.") : error))
                case .cancelled: if self.continuation != nil { self.finish(.failure(CancellationError())) }
                default: break
                }
            }
            let timeout = DispatchWorkItem { [weak self] in self?.finish(.failure(URLError(.timedOut))) }
            self.timeout = timeout
            self.queue.asyncAfter(deadline: .now() + 115, execute: timeout)
            connection.start(queue: self.queue)
        }
    }
    func cancel() { queue.async { self.cancelled = true; if self.continuation != nil { self.finish(.failure(CancellationError())) } } }
    private func send() {
        guard let url = request.url, let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { finish(.failure(PanelError(message: "Ungültige API-Adresse."))); return }
        let target = (components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath) + (components.percentEncodedQuery.map { "?" + $0 } ?? "")
        let host = components.host ?? ""
        let authority = (host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host) + (components.port.map { ":\($0)" } ?? "")
        let body = request.httpBody ?? Data()
        var headers = request.allHTTPHeaderFields ?? [:]
        for key in Array(headers.keys) where ["host", "content-length", "connection", "accept-encoding"].contains(key.lowercased()) { headers.removeValue(forKey: key) }
        headers["Host"] = authority
        headers["Connection"] = "close"
        headers["Accept-Encoding"] = "identity"
        headers["Content-Length"] = String(body.count)
        var bytes = Data(((request.httpMethod ?? "GET") + " " + target + " HTTP/1.1\r\n" + headers.sorted { $0.key < $1.key }.map { $0.key + ": " + $0.value + "\r\n" }.joined() + "\r\n").utf8)
        bytes.append(body)
        connection?.send(content: bytes, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            if let error { self.finish(.failure(error)) } else { self.receive() }
        })
    }
    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 262144) { [weak self] data, _, complete, error in
            guard let self, self.continuation != nil else { return }
            if let data { self.buffer.append(data) }
            do {
                if let message = try PinnedHTTPS.decode(self.buffer, complete: complete) {
                    guard let url = self.request.url, let response = HTTPURLResponse(url: url, statusCode: message.status, httpVersion: "HTTP/1.1", headerFields: message.headers) else { throw PanelError(message: "Ungültige HTTPS-Antwort.") }
                    self.finish(.success((message.data, response)))
                } else if let error { self.finish(.failure(error)) } else { self.receive() }
            } catch { self.finish(.failure(error)) }
        }
    }
    private func finish(_ result: Result<(Data, URLResponse), Error>) {
        guard let continuation else { return }
        self.continuation = nil
        timeout?.cancel()
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        self.continuation = nil
        continuation.resume(with: result)
    }
}
