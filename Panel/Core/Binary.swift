import Foundation
import zlib

/// Bounded gzip/zlib/raw-deflate codec shared by NBT and ZIP metadata readers.
enum BinaryCodec {
    static func inflateData(_ input: Data, window: Int32 = 47, limit: Int = 16 * 1024 * 1024) throws -> Data {
        var stream = z_stream()
        guard inflateInit2_(&stream, window, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw PanelError(message: "Dekompression konnte nicht starten.") }
        defer { inflateEnd(&stream) }
        return try input.withUnsafeBytes { source in
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: source.bindMemory(to: Bytef.self).baseAddress); stream.avail_in = uInt(input.count)
            var output = Data(); var status: Int32 = Z_OK
            repeat {
                var chunk = [UInt8](repeating: 0, count: 32768)
                let count = chunk.count
                status = chunk.withUnsafeMutableBytes { target in
                    stream.next_out = target.bindMemory(to: Bytef.self).baseAddress; stream.avail_out = uInt(count)
                    return inflate(&stream, Z_NO_FLUSH)
                }
                guard status == Z_OK || status == Z_STREAM_END else { throw PanelError(message: "Beschädigte oder nicht unterstützte komprimierte Datei.") }
                let n = count - Int(stream.avail_out)
                guard output.count + n <= limit else { throw PanelError(message: "Entpackte Datei überschreitet das sichere Größenlimit.") }
                output.append(contentsOf: chunk.prefix(n))
                guard n > 0 || status == Z_STREAM_END else { throw PanelError(message: "Unvollständige komprimierte Datei.") }
            } while status != Z_STREAM_END
            guard stream.avail_in == 0 else { throw PanelError(message: "Zusätzliche Daten nach dem komprimierten Inhalt.") }
            return output
        }
    }
    static func compress(_ input: Data, window: Int32 = 31) throws -> Data {
        var stream = z_stream()
        guard deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, window, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw PanelError(message: "Kompression konnte nicht starten.") }
        defer { deflateEnd(&stream) }
        return try input.withUnsafeBytes { source in
            stream.next_in = UnsafeMutablePointer<Bytef>(mutating: source.bindMemory(to: Bytef.self).baseAddress); stream.avail_in = uInt(input.count)
            var output = Data(); var status: Int32 = Z_OK
            repeat {
                var chunk = [UInt8](repeating: 0, count: 32768); let count = chunk.count
                status = chunk.withUnsafeMutableBytes { target in stream.next_out = target.bindMemory(to: Bytef.self).baseAddress; stream.avail_out = uInt(count); return deflate(&stream, Z_FINISH) }
                guard status == Z_OK || status == Z_STREAM_END else { throw PanelError(message: "Kompression fehlgeschlagen.") }
                output.append(contentsOf: chunk.prefix(count - Int(stream.avail_out)))
            } while status != Z_STREAM_END
            return output
        }
    }
}
extension Data {
    func uintBE(_ start: Int, _ count: Int) -> UInt64 { self[start..<start + count].reduce(0) { ($0 << 8) | UInt64($1) } }
    func uintLE(_ start: Int, _ count: Int) -> UInt64 { self[start..<start + count].reversed().reduce(0) { ($0 << 8) | UInt64($1) } }
    static func bigEndian(_ value: UInt64, bytes: Int) -> Data { Data((0..<bytes).reversed().map { UInt8(truncatingIfNeeded: value >> ($0 * 8)) }) }
}
