import SwiftUI
import UIKit

// MARK: - Colors

extension UIColor {
    convenience init(hex: String, alpha: CGFloat = 1) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        var value: UInt64 = 0
        Scanner(string: s).scanHexInt64(&value)
        let r, g, b: CGFloat
        if s.count == 3 {
            r = CGFloat((value >> 8) & 0xF) / 15
            g = CGFloat((value >> 4) & 0xF) / 15
            b = CGFloat(value & 0xF) / 15
        } else {
            r = CGFloat((value >> 16) & 0xFF) / 255
            g = CGFloat((value >> 8) & 0xFF) / 255
            b = CGFloat(value & 0xFF) / 255
        }
        self.init(red: r, green: g, blue: b, alpha: alpha)
    }

    var hexString: String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02x%02x%02x",
                      Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
    }
}

extension Color {
    init(hex: String) { self.init(uiColor: UIColor(hex: hex)) }

    /// A colour that switches with the system appearance.
    static func adaptive(_ light: String, _ dark: String) -> Color {
        Color(uiColor: UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }

    static let canvasBackground = Color.adaptive("#F4F2EE", "#161616")
    static let cardBackground = Color.adaptive("#FFFFFF", "#232323")
    static let photoBackground = Color.adaptive("#F1EEE8", "#2A2A2A")
    static let wallColor = Color.adaptive("#4A4A4A", "#9A9A9A")
}

// MARK: - Numbers & dates

extension Double {
    /// "1" for 1.0, "1.5" for 1.5 — quantities read better without trailing zeros.
    var clean: String {
        if self == rounded() && abs(self) < 1e12 { return String(Int(self)) }
        return String(format: "%.2f", self)
            .replacingOccurrences(of: "0+$", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\.$", with: "", options: .regularExpression)
    }

    var metres: String { String(format: "%.1f", self / 100) }
}

func clamp<T: Comparable>(_ value: T, _ low: T, _ high: T) -> T {
    min(max(value, low), high)
}

func snap(_ value: Double, to step: Double) -> Double {
    (value / step).rounded() * step
}

extension Date {
    var shortDate: String { formatted(.dateTime.year().month().day()) }

    var startOfDay: Date { Calendar.current.startOfDay(for: self) }

    /// Whole days from today until this date (negative when in the past).
    var daysFromToday: Int {
        Calendar.current.dateComponents([.day], from: Date().startOfDay, to: startOfDay).day ?? 0
    }
}

extension Collection {
    subscript(safe index: Index) -> Element? { indices.contains(index) ? self[index] : nil }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var nilIfEmpty: String? { trimmed.isEmpty ? nil : self }
}

// MARK: - Decoding helpers

extension KeyedDecodingContainer {
    /// Decodes a value, falling back to a default when it is missing or
    /// malformed. Synced records must survive fields added by newer clients
    /// and fields removed by older ones.
    func value<T: Decodable>(_ key: Key, _ fallback: @autoclosure () -> T) -> T {
        ((try? decodeIfPresent(T.self, forKey: key)) ?? nil) ?? fallback()
    }

    func optional<T: Decodable>(_ key: Key) -> T? {
        (try? decodeIfPresent(T.self, forKey: key)) ?? nil
    }
}

// MARK: - Haptics

enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
}
