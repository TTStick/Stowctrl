import Compression
import Foundation
import zlib

/*
 * Full backups that need no server: a standard .zip holding
 *   nook-backup.json  – every record, in the same shape the sync server uses
 *   files/<id>        – the photos
 * The Android app reads and writes the same format, so a backup made on one
 * platform restores on the other.
 */

enum ZipError: LocalizedError {
    case notZip, unsupported, corrupt, tooLarge
    var errorDescription: String? {
        switch self {
        case .notZip: return "这不是万物收纳的备份文件"
        case .unsupported: return "备份文件使用了不支持的压缩方式"
        case .corrupt: return "备份文件已损坏"
        case .tooLarge: return "备份太大（超过 4 GB）"
        }
    }
}

private extension Data {
    mutating func le16(_ v: UInt16) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
    mutating func le32(_ v: UInt32) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
    func u16(_ at: Int) -> UInt16 { UInt16(self[startIndex + at]) | UInt16(self[startIndex + at + 1]) << 8 }
    func u32(_ at: Int) -> UInt32 { UInt32(u16(at)) | UInt32(u16(at + 2)) << 16 }
}

private func crc(_ data: Data) -> UInt32 {
    data.withUnsafeBytes { raw in
        UInt32(crc32(0, raw.bindMemory(to: Bytef.self).baseAddress, uInt(raw.count)))
    }
}

/// Streams "stored" (uncompressed) entries; photos are already compressed.
final class ZipWriter {
    private let handle: FileHandle
    private var central = Data()
    private var count = 0
    private var offset: UInt64 = 0

    init(url: URL) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try FileHandle(forWritingTo: url)
    }

    func add(_ name: String, _ data: Data) throws {
        let nameData = Data(name.utf8)
        guard count < 65_535, offset + UInt64(data.count) + 4096 < UInt64(UInt32.max) else { throw ZipError.tooLarge }
        let sum = crc(data)
        let size = UInt32(data.count)
        var local = Data()
        local.le32(0x0403_4b50); local.le16(20); local.le16(0x0800); local.le16(0)  // UTF-8 names, stored
        local.le16(0); local.le16(0x21)                                               // 1980-01-01
        local.le32(sum); local.le32(size); local.le32(size)
        local.le16(UInt16(nameData.count)); local.le16(0)
        local.append(nameData)
        try handle.write(contentsOf: local)
        try handle.write(contentsOf: data)

        var c = Data()
        c.le32(0x0201_4b50); c.le16(20); c.le16(20); c.le16(0x0800); c.le16(0)
        c.le16(0); c.le16(0x21)
        c.le32(sum); c.le32(size); c.le32(size)
        c.le16(UInt16(nameData.count)); c.le16(0); c.le16(0); c.le16(0); c.le16(0); c.le32(0)
        c.le32(UInt32(offset))
        c.append(nameData)
        central.append(c)
        offset += UInt64(local.count + data.count)
        count += 1
    }

    func finish() throws {
        try handle.write(contentsOf: central)
        var end = Data()
        end.le32(0x0605_4b50); end.le16(0); end.le16(0)
        end.le16(UInt16(count)); end.le16(UInt16(count))
        end.le32(UInt32(central.count)); end.le32(UInt32(offset)); end.le16(0)
        try handle.write(contentsOf: end)
        try handle.close()
    }
}

/// Reads stored and deflated entries (Android's java.util.zip deflates).
struct ZipReader {
    struct Entry {
        let name: String
        let method: UInt16
        let crc: UInt32
        let compressedSize: Int
        let size: Int
        let localOffset: Int
    }

    private let data: Data
    let entries: [Entry]

    init(url: URL) throws {
        try self.init(data: Data(contentsOf: url, options: .alwaysMapped))
    }

    init(data: Data) throws {
        self.data = data
        guard data.count >= 22 else { throw ZipError.notZip }
        var eocd = -1
        var i = data.count - 22
        let floor = max(0, data.count - 65_557)
        while i >= floor {
            if data.u32(i) == 0x0605_4b50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw ZipError.notZip }
        let total = Int(data.u16(eocd + 10))
        var p = Int(data.u32(eocd + 16))
        var list: [Entry] = []
        for _ in 0..<total {
            guard p + 46 <= data.count, data.u32(p) == 0x0201_4b50 else { throw ZipError.corrupt }
            let nameLen = Int(data.u16(p + 28)), extraLen = Int(data.u16(p + 30)), commentLen = Int(data.u16(p + 32))
            guard p + 46 + nameLen <= data.count else { throw ZipError.corrupt }
            let nameBytes = data.subdata(in: (data.startIndex + p + 46)..<(data.startIndex + p + 46 + nameLen))
            list.append(Entry(name: String(decoding: nameBytes, as: UTF8.self), method: data.u16(p + 10), crc: data.u32(p + 16),
                              compressedSize: Int(data.u32(p + 20)), size: Int(data.u32(p + 24)), localOffset: Int(data.u32(p + 42))))
            p += 46 + nameLen + extraLen + commentLen
        }
        entries = list
    }

    func read(_ entry: Entry) throws -> Data {
        let lo = entry.localOffset
        guard lo + 30 <= data.count, data.u32(lo) == 0x0403_4b50 else { throw ZipError.corrupt }
        let start = lo + 30 + Int(data.u16(lo + 26)) + Int(data.u16(lo + 28))
        guard start + entry.compressedSize <= data.count else { throw ZipError.corrupt }
        let raw = data.subdata(in: (data.startIndex + start)..<(data.startIndex + start + entry.compressedSize))
        let out: Data
        switch entry.method {
        case 0:
            out = raw
        case 8:
            guard entry.size > 0 else { return Data() }
            var buffer = Data(count: entry.size)
            let written = buffer.withUnsafeMutableBytes { dst in
                raw.withUnsafeBytes { src in
                    compression_decode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, entry.size,
                                              src.bindMemory(to: UInt8.self).baseAddress!, raw.count, nil, COMPRESSION_ZLIB)
                }
            }
            guard written == entry.size else { throw ZipError.corrupt }
            out = buffer
        default:
            throw ZipError.unsupported
        }
        guard crc(out) == entry.crc else { throw ZipError.corrupt }
        return out
    }

    func read(_ name: String) throws -> Data? {
        guard let e = entries.first(where: { $0.name == name }) else { return nil }
        return try read(e)
    }
}

enum Backup {
    static let manifestName = "nook-backup.json"

    struct Manifest: Codable {
        var format = "nook-backup"
        var version = 1
        var exportedAt: Date
        var platform: String
        var homeName: String
        var records: [SyncChange]
    }

    struct Loaded {
        let manifest: Manifest
        let reader: ZipReader
        var photoCount: Int { reader.entries.filter { $0.name.hasPrefix("files/") }.count }
        func count(_ entity: String) -> Int { manifest.records.filter { $0.entity == entity && !$0.deleted }.count }
    }

    /// Photo ids are "<UUID>.jpg/png"; anything else (e.g. "../x") is ignored.
    static func isSafeFileId(_ id: String) -> Bool {
        id.range(of: "^[A-Za-z0-9-]{1,64}\\.(jpg|jpeg|png|heic|webp)$", options: [.regularExpression, .caseInsensitive]) != nil
    }

    @MainActor
    static func export(store: HomeStore) async throws -> URL {
        let manifest = Manifest(exportedAt: Date(), platform: "ios", homeName: store.settings.homeName, records: store.backupChanges())
        let stamp = Date().formatted(.iso8601.year().month().day())
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("万物收纳备份-\(stamp).zip")
        try? FileManager.default.removeItem(at: url)
        let writer = try ZipWriter(url: url)
        try writer.add(manifestName, JSONCoding.encoder.encode(manifest))
        let ids = Set(store.items.values.flatMap { [$0.photoId, $0.originalPhotoId].compactMap { $0 } })
        for id in ids.sorted() where isSafeFileId(id) {
            // Photos only on the server get fetched first when signed in.
            if let data = await store.files.loadData(id) { try writer.add("files/\(id)", data) }
        }
        try writer.finish()
        return url
    }

    static func load(_ url: URL) throws -> Loaded {
        let reader = try ZipReader(url: url)
        guard let raw = try reader.read(manifestName),
              let manifest = try? JSONCoding.decoder.decode(Manifest.self, from: raw),
              manifest.format == "nook-backup" else { throw ZipError.notZip }
        return Loaded(manifest: manifest, reader: reader)
    }

    @MainActor
    static func restore(_ loaded: Loaded, into store: HomeStore, replace: Bool) throws -> HomeStore.ImportSummary {
        var photos = 0
        for entry in loaded.reader.entries where entry.name.hasPrefix("files/") {
            let id = String(entry.name.dropFirst("files/".count))
            guard isSafeFileId(id) else { continue }
            if !store.files.exists(id) { try store.files.write(loaded.reader.read(entry), id: id) }
            store.registerUpload(id)
            photos += 1
        }
        var summary = store.importBackup(loaded.manifest.records, replace: replace)
        summary.photos = photos
        return summary
    }
}
