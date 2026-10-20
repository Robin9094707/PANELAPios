import Foundation

struct NBTNode: Identifiable, Sendable {
    var id = UUID()
    var type: UInt8
    var name: String
    var nameBytes: Data? = nil
    var scalar = Data()
    var children: [NBTNode] = []
    var listType: UInt8 = 0
    var container: Bool { type == 9 || type == 10 }
    var typeName: String { ["End", "Byte", "Short", "Int", "Long", "Float", "Double", "Byte[]", "String", "List", "Compound", "Int[]", "Long[]"][Int(type)] }
    var text: String {
        switch type {
        case 1: return scalar.isEmpty ? "0" : String(Int8(bitPattern: scalar[0]))
        case 2: return String(Int16(bitPattern: UInt16(scalar.uintBE(0, 2))))
        case 3: return String(Int32(bitPattern: UInt32(scalar.uintBE(0, 4))))
        case 4: return String(Int64(bitPattern: scalar.uintBE(0, 8)))
        case 5: return String(Float(bitPattern: UInt32(scalar.uintBE(0, 4))))
        case 6: return String(Double(bitPattern: scalar.uintBE(0, 8)))
        case 8: return (try? ModifiedUTF8.decode(scalar)) ?? ""
        case 7, 11, 12:
            let width = type == 7 ? 1 : type == 11 ? 4 : 8
            return stride(from: 0, to: scalar.count, by: width).prefix(100000).map { offset in
                let n = scalar.uintBE(offset, width)
                return width == 1 ? String(Int8(bitPattern: UInt8(n))) : width == 4 ? String(Int32(bitPattern: UInt32(n))) : String(Int64(bitPattern: n))
            }.joined(separator: ", ")
        default: return "\(children.count) Einträge"
        }
    }
    mutating func setText(_ value: String) throws {
        let t = value.trimmingCharacters(in: .whitespacesAndNewlines)
        var result: Data?
        switch type {
        case 1: if let n = Int8(t) { result = Data([UInt8(bitPattern: n)]) }
        case 2: if let n = Int16(t) { result = .bigEndian(UInt64(UInt16(bitPattern: n)), bytes: 2) }
        case 3: if let n = Int32(t) { result = .bigEndian(UInt64(UInt32(bitPattern: n)), bytes: 4) }
        case 4: if let n = Int64(t) { result = .bigEndian(UInt64(bitPattern: n), bytes: 8) }
        case 5: if let n = Float(t), n.isFinite { result = .bigEndian(UInt64(n.bitPattern), bytes: 4) }
        case 6: if let n = Double(t), n.isFinite { result = .bigEndian(n.bitPattern, bytes: 8) }
        case 8: result = try ModifiedUTF8.encode(value)
        case 7, 11, 12:
            let parts = t.isEmpty ? [] : t.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            guard parts.count <= 100000 else { throw PanelError(message: "Zu viele Array-Werte für den Editor.") }
            var data = Data()
            for part in parts {
                if type == 7, let n = Int8(part) { data.append(UInt8(bitPattern: n)) }
                else if type == 11, let n = Int32(part) { data.append(.bigEndian(UInt64(UInt32(bitPattern: n)), bytes: 4)) }
                else if type == 12, let n = Int64(part) { data.append(.bigEndian(UInt64(bitPattern: n), bytes: 8)) }
                else { throw PanelError(message: "Ungültiger \(typeName)-Wert: \(part)") }
            }; result = data
        default: break
        }
        guard let result else { throw PanelError(message: "Ungültiger Wert für \(typeName). Ganzzahlbereich und Zahlenformat beachten.") }
        scalar = result
    }
    func child(_ key: String) -> NBTNode? { children.first { $0.name == key } }
    var number: Double? { Double(text) }
}
/// Java's DataInput/DataOutput Modified UTF-8, including NUL and surrogate pairs.
enum ModifiedUTF8 {
    static func decode(_ data: Data) throws -> String {
        let bytes = Array(data); var units: [UInt16] = []; var i = 0
        while i < bytes.count {
            let a = bytes[i]
            if a < 0x80 { units.append(UInt16(a)); i += 1 }
            else if a & 0xe0 == 0xc0, i + 1 < bytes.count, bytes[i + 1] & 0xc0 == 0x80 {
                units.append((UInt16(a & 0x1f) << 6) | UInt16(bytes[i + 1] & 0x3f)); i += 2
            } else if a & 0xf0 == 0xe0, i + 2 < bytes.count, bytes[i + 1] & 0xc0 == 0x80, bytes[i + 2] & 0xc0 == 0x80 {
                units.append((UInt16(a & 0x0f) << 12) | (UInt16(bytes[i + 1] & 0x3f) << 6) | UInt16(bytes[i + 2] & 0x3f)); i += 3
            } else { throw PanelError(message: "Ungültige NBT-Zeichenkodierung.") }
        }
        return String(decoding: units, as: UTF16.self)
    }
    static func encode(_ value: String) throws -> Data {
        var data = Data()
        for n in value.utf16 {
            if n > 0 && n < 0x80 { data.append(UInt8(n)) }
            else if n < 0x800 { data.append(UInt8(0xc0 | (n >> 6))); data.append(UInt8(0x80 | (n & 0x3f))) }
            else { data.append(UInt8(0xe0 | (n >> 12))); data.append(UInt8(0x80 | ((n >> 6) & 0x3f))); data.append(UInt8(0x80 | (n & 0x3f))) }
        }
        guard data.count <= 65535 else { throw PanelError(message: "NBT-String länger als 65535 Bytes.") }; return data
    }
}
struct NBTDocument: Sendable {
    enum Compression: Sendable, Equatable { case gzip, zlib, none }
    var root: NBTNode
    var compression: Compression
    static func decode(_ input: Data) throws -> NBTDocument {
        guard !input.isEmpty && input.count <= 8 * 1024 * 1024 else { throw PanelError(message: "NBT-Datei leer oder größer als 8 MiB.") }
        let compression: Compression = input.starts(with: [0x1f, 0x8b]) ? .gzip : input[0] == 0x78 ? .zlib : .none
        let data = compression == .none ? input : try BinaryCodec.inflateData(input)
        var reader = NBTReader(data: data)
        let type = try reader.byte(); guard type == 10 else { throw PanelError(message: "Keine Java-NBT-Datei mit Compound-Wurzel. Bedrock-LevelDB und Region-Dateien werden nicht unterstützt.") }
        let raw = try reader.stringBytes(); var root = try reader.node(type, depth: 0); root.name = try ModifiedUTF8.decode(raw); root.nameBytes = raw
        guard reader.offset == data.count else { throw PanelError(message: "Zusätzliche Daten nach der NBT-Wurzel.") }
        return NBTDocument(root: root, compression: compression)
    }
    func encode() throws -> Data {
        var data = Data([root.type]); try Self.writeName(root, to: &data); try Self.write(root, to: &data, depth: 0)
        guard data.count <= 16 * 1024 * 1024 else { throw PanelError(message: "NBT-Ausgabe zu groß.") }
        switch compression { case .none: return data; case .gzip: return try BinaryCodec.compress(data); case .zlib: return try BinaryCodec.compress(data, window: 15) }
    }
    static func writeName(_ node: NBTNode, to data: inout Data) throws {
        let name = try node.nameBytes ?? ModifiedUTF8.encode(node.name)
        guard name.count <= 65535 else { throw PanelError(message: "NBT-Name zu lang.") }
        data.append(.bigEndian(UInt64(name.count), bytes: 2)); data.append(name)
    }
    static func write(_ node: NBTNode, to data: inout Data, depth: Int) throws {
        guard depth <= 64 && data.count <= 16 * 1024 * 1024 else { throw PanelError(message: "NBT-Struktur zu groß oder zu tief.") }
        switch node.type {
        case 1...6: data.append(node.scalar)
        case 8: guard node.scalar.count <= 65535 else { throw PanelError(message: "NBT-String zu lang.") }; data.append(.bigEndian(UInt64(node.scalar.count), bytes: 2)); data.append(node.scalar)
        case 7, 11, 12:
            let width = node.type == 7 ? 1 : node.type == 11 ? 4 : 8
            data.append(.bigEndian(UInt64(node.scalar.count / width), bytes: 4)); data.append(node.scalar)
        case 9:
            data.append(node.listType); data.append(.bigEndian(UInt64(node.children.count), bytes: 4))
            for child in node.children { guard child.type == node.listType else { throw PanelError(message: "Gemischte NBT-Listentypen.") }; try write(child, to: &data, depth: depth + 1) }
        case 10:
            for child in node.children { data.append(child.type); try writeName(child, to: &data); try write(child, to: &data, depth: depth + 1) }; data.append(0)
        default: throw PanelError(message: "Unbekannter NBT-Typ.")
        }
    }
}
private struct NBTReader {
    let data: Data
    var offset = 0
    var nodes = 0
    mutating func take(_ count: Int) throws -> Data {
        guard count >= 0 && count <= data.count - offset else { throw PanelError(message: "Unvollständige NBT-Datei.") }
        defer { offset += count }; return Data(data[offset..<offset + count])
    }
    mutating func byte() throws -> UInt8 { try take(1)[0] }
    mutating func count(width: Int) throws -> Int { let n = Int32(bitPattern: UInt32(try take(4).uintBE(0, 4))); guard n >= 0 && Int(n) <= (data.count - offset) / max(1, width) else { throw PanelError(message: "Ungültige NBT-Länge.") }; return Int(n) }
    mutating func stringBytes() throws -> Data { let n = Int(try take(2).uintBE(0, 2)); return try take(n) }
    mutating func node(_ type: UInt8, depth: Int) throws -> NBTNode {
        nodes += 1; guard nodes <= 100000 && depth <= 64 else { throw PanelError(message: "NBT-Knotenlimit oder Verschachtelungslimit erreicht.") }
        var node = NBTNode(type: type, name: "")
        switch type {
        case 1...6: node.scalar = try take([0, 1, 2, 4, 8, 4, 8][Int(type)])
        case 8: node.scalar = try stringBytes(); _ = try ModifiedUTF8.decode(node.scalar)
        case 7, 11, 12: let width = type == 7 ? 1 : type == 11 ? 4 : 8; let n = try count(width: width); node.scalar = try take(n * width)
        case 9:
            node.listType = try byte(); let n = try count(width: 1)
            guard node.listType <= 12 && (n == 0 || node.listType != 0) else { throw PanelError(message: "Ungültiger NBT-Listentyp.") }
            for _ in 0..<n { node.children.append(try self.node(node.listType, depth: depth + 1)) }
        case 10:
            while true { let tag = try byte(); if tag == 0 { break }; let raw = try stringBytes(); var child = try self.node(tag, depth: depth + 1); child.name = try ModifiedUTF8.decode(raw); child.nameBytes = raw; node.children.append(child) }
        default: throw PanelError(message: "Unbekannter NBT-Typ \(type).")
        }; return node
    }
}
