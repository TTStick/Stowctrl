import CoreImage
import CoreImage.CIFilterBuiltins
import UIKit
import Vision

/// On-device background removal with Vision's foreground instance mask.
/// Keeps the observation around so the user can pick which subjects stay.
final class CutoutSession {
    let source: UIImage
    let instanceCount: Int
    private let observation: VNInstanceMaskObservation
    private let handler: VNImageRequestHandler

    private init(source: UIImage, observation: VNInstanceMaskObservation, handler: VNImageRequestHandler) {
        self.source = source
        self.observation = observation
        self.handler = handler
        self.instanceCount = observation.allInstances.count
    }

    enum Failure: LocalizedError {
        case noSubject
        case unsupported
        var errorDescription: String? {
            switch self {
            case .noSubject: return "没有识别到主体，可以换个角度或背景再拍一张"
            case .unsupported: return "这台设备不支持端侧抠图"
            }
        }
    }

    static func make(_ image: UIImage) async throws -> CutoutSession {
        let upright = ImageEncoding.normalized(image, maxSide: 2048)
        guard let cg = upright.cgImage else { throw Failure.unsupported }
        return try await Task.detached(priority: .userInitiated) {
            let request = VNGenerateForegroundInstanceMaskRequest()
            let handler = VNImageRequestHandler(cgImage: cg, orientation: .up)
            do {
                try handler.perform([request])
            } catch {
                throw Failure.unsupported
            }
            guard let obs = request.results?.first, !obs.allInstances.isEmpty else { throw Failure.noSubject }
            return CutoutSession(source: upright, observation: obs, handler: handler)
        }.value
    }

    /// Transparent PNG-ready image of the chosen subjects, trimmed with a small margin.
    func render(instances: IndexSet? = nil) async throws -> UIImage {
        let chosen = instances ?? observation.allInstances
        let obs = observation, handler = handler
        return try await Task.detached(priority: .userInitiated) {
            let buffer = try obs.generateMaskedImage(ofInstances: chosen, from: handler, croppedToInstancesExtent: true)
            let ci = CIImage(cvPixelBuffer: buffer)
            let context = CIContext()
            guard let cg = context.createCGImage(ci, from: ci.extent) else { throw Failure.noSubject }
            return Self.padded(UIImage(cgImage: cg), fraction: 0.04)
        }.value
    }

    static func padded(_ image: UIImage, fraction: CGFloat) -> UIImage {
        let pad = max(image.size.width, image.size.height) * fraction
        let size = CGSize(width: image.size.width + pad * 2, height: image.size.height + pad * 2)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(x: pad, y: pad, width: image.size.width, height: image.size.height))
        }
    }
}

/// Dominant colours of a garment, ignoring transparent and near-white pixels.
enum ColorExtractor {
    static func palette(of image: UIImage, count: Int = 3) -> [String] {
        let side = 64
        guard let cg = image.cgImage else { return [] }
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        guard let ctx = CGContext(data: &pixels, width: side, height: side, bitsPerComponent: 8,
                                  bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return [] }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))

        var samples: [SIMD3<Float>] = []
        samples.reserveCapacity(side * side)
        for i in stride(from: 0, to: pixels.count, by: 4) {
            let a = Float(pixels[i + 3])
            guard a > 200 else { continue }
            let rgb = SIMD3<Float>(Float(pixels[i]), Float(pixels[i + 1]), Float(pixels[i + 2])) * (255 / a)
            samples.append(rgb)
        }
        // Photos without transparency: use the central area only.
        if samples.count == side * side {
            samples = []
            for y in side / 4..<(side * 3 / 4) {
                for x in side / 4..<(side * 3 / 4) {
                    let i = (y * side + x) * 4
                    samples.append(SIMD3(Float(pixels[i]), Float(pixels[i + 1]), Float(pixels[i + 2])))
                }
            }
        }
        guard samples.count >= 10 else { return [] }

        let k = min(5, samples.count)
        var centers = (0..<k).map { samples[$0 * samples.count / k] }
        var assignment = [Int](repeating: 0, count: samples.count)
        for _ in 0..<12 {
            for (i, s) in samples.enumerated() {
                var best = 0
                var bestDist = Float.greatestFiniteMagnitude
                for (j, c) in centers.enumerated() {
                    let d = s - c
                    let dist = (d * d).sum()
                    if dist < bestDist { bestDist = dist; best = j }
                }
                assignment[i] = best
            }
            var sums = [SIMD3<Float>](repeating: .zero, count: k)
            var counts = [Int](repeating: 0, count: k)
            for (i, s) in samples.enumerated() { sums[assignment[i]] += s; counts[assignment[i]] += 1 }
            for j in 0..<k where counts[j] > 0 { centers[j] = sums[j] / Float(counts[j]) }
        }
        var counts = [Int](repeating: 0, count: k)
        for a in assignment { counts[a] += 1 }

        var result: [(SIMD3<Float>, Int)] = []
        for j in (0..<k).sorted(by: { counts[$0] > counts[$1] }) where counts[j] > samples.count / 30 {
            let c = centers[j]
            // Drop colours almost identical to one already chosen.
            if result.contains(where: { let d = $0.0 - c; return (d * d).sum() < 900 }) { continue }
            result.append((c, counts[j]))
        }
        return result.prefix(count).map { entry in
            let c = entry.0
            return String(format: "#%02x%02x%02x", Int(clamp(c.x, 0, 255)), Int(clamp(c.y, 0, 255)), Int(clamp(c.z, 0, 255)))
        }
    }
}
