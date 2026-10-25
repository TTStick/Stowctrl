import Foundation
import ImageIO
import UIKit

/// Photo files on disk, named by id ("<uuid>.jpg" / "<uuid>.png").
final class FileStore: @unchecked Sendable {
    let directory: URL
    /// Fetches a file this device doesn't have yet (set by the sync engine).
    var remoteFetcher: ((String) async -> Data?)?

    private let cache = NSCache<NSString, UIImage>()
    private var inFlight: [String: Task<Data?, Never>] = [:]
    private let lock = NSLock()

    init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        cache.countLimit = 300
    }

    func url(for id: String) -> URL { directory.appendingPathComponent(id) }

    func exists(_ id: String) -> Bool { FileManager.default.fileExists(atPath: url(for: id).path) }

    func write(_ data: Data, id: String) throws {
        try data.write(to: url(for: id), options: .atomic)
    }

    func data(_ id: String) -> Data? { try? Data(contentsOf: url(for: id)) }

    func delete(_ id: String) {
        try? FileManager.default.removeItem(at: url(for: id))
        removeFromCache(id)
    }

    func deleteAll() {
        try? FileManager.default.removeItem(at: directory)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        cache.removeAllObjects()
    }

    func allIds() -> [String] {
        (try? FileManager.default.contentsOfDirectory(atPath: directory.path))?.filter { !$0.hasPrefix(".") } ?? []
    }

    private func removeFromCache(_ id: String) {
        for size in [64, 128, 256, 512, 1024, 2048] { cache.removeObject(forKey: "\(id)@\(size)" as NSString) }
    }

    /// Local bytes, or the server's copy (saved locally) when missing.
    func loadData(_ id: String) async -> Data? {
        if let d = data(id) { return d }
        guard let fetcher = remoteFetcher else { return nil }
        let task: Task<Data?, Never> = lock.withLock {
            if let existing = inFlight[id] { return existing }
            let created = Task<Data?, Never> { await fetcher(id) }
            inFlight[id] = created
            return created
        }
        let result = await task.value
        lock.withLock { inFlight[id] = nil }
        if let result { try? write(result, id: id) }
        return result
    }

    /// Downsampled image for display, cached in memory.
    func image(_ id: String, maxPixel: CGFloat) async -> UIImage? {
        let bucket = [64, 128, 256, 512, 1024, 2048].first { CGFloat($0) >= maxPixel } ?? 2048
        let key = "\(id)@\(bucket)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        guard let data = await loadData(id) else { return nil }
        let image = await Task.detached(priority: .userInitiated) {
            Self.downsample(data, maxPixel: CGFloat(bucket))
        }.value
        if let image { cache.setObject(image, forKey: key) }
        return image
    }

    func cachedImage(_ id: String, maxPixel: CGFloat) -> UIImage? {
        let bucket = [64, 128, 256, 512, 1024, 2048].first { CGFloat($0) >= maxPixel } ?? 2048
        return cache.object(forKey: "\(id)@\(bucket)" as NSString)
    }

    static func downsample(_ data: Data, maxPixel: CGFloat) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options) else { return nil }
        let thumbOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ] as CFDictionary
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbOptions) else { return nil }
        return UIImage(cgImage: cg)
    }
}

enum ImageEncoding {
    /// Upright copy scaled so its longest side is at most `maxSide`.
    static func normalized(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let size = image.size
        let scale = min(1, maxSide / max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    static func jpeg(_ image: UIImage, maxSide: CGFloat = 1600) -> Data? {
        normalized(image, maxSide: maxSide).jpegData(compressionQuality: 0.82)
    }

    static func png(_ image: UIImage, maxSide: CGFloat = 1024) -> Data? {
        normalized(image, maxSide: maxSide).pngData()
    }
}
