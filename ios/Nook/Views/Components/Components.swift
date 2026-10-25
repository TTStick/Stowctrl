import SwiftUI
import UIKit

// MARK: - Icons

/// Renders a sanitised SVG icon in the current foreground style.
struct SVGIconView: View {
    let svg: String

    var body: some View {
        Canvas { ctx, size in
            guard let doc = SVGCache.document(svg) else { return }
            let vb = doc.viewBox
            let s = min(size.width / vb.width, size.height / vb.height)
            ctx.translateBy(x: (size.width - vb.width * s) / 2 - vb.minX * s,
                            y: (size.height - vb.height * s) / 2 - vb.minY * s)
            ctx.scaleBy(x: s, y: s)
            for shape in doc.shapes {
                var c = ctx
                c.opacity = shape.opacity
                let p = Path(shape.path)
                if shape.fill { c.fill(p, with: .foreground, style: FillStyle(eoFill: shape.evenOdd)) }
                if shape.stroke {
                    c.stroke(p, with: .foreground, style: StrokeStyle(lineWidth: shape.strokeWidth,
                                                                      lineCap: shape.lineCap, lineJoin: shape.lineJoin))
                }
            }
        }
    }
}

@MainActor
enum SVGCache {
    private static var cache: [String: SVGDocument] = [:]
    private static var failures: Set<String> = []

    static func document(_ svg: String) -> SVGDocument? {
        if let d = cache[svg] { return d }
        if failures.contains(svg) { return nil }
        guard let d = SVGDocument.parse(svg) else { failures.insert(svg); return nil }
        if cache.count > 400 { cache.removeAll() }
        cache[svg] = d
        return d
    }
}

enum SymbolCheck {
    /// Falls back when a symbol doesn't exist on this OS version.
    static func safe(_ name: String, fallback: String = "shippingbox") -> String {
        UIImage(systemName: name) != nil ? name : fallback
    }
}

/// An item's icon (symbol, emoji or AI-drawn), on a tinted tile.
struct IconTile: View {
    let icon: IconRef?
    var fallbackSymbol: String = "shippingbox"
    var tint: Color = .accentColor
    var size: CGFloat = 44
    @Environment(HomeStore.self) private var store

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
                .fill(tint.opacity(0.14))
            content
                .foregroundStyle(tint)
        }
        .frame(width: size, height: size)
    }

    @ViewBuilder private var content: some View {
        switch icon?.kind {
        case .emoji:
            Text(icon!.value).font(.system(size: size * 0.55))
        case .custom:
            if let id = UUID(uuidString: icon!.value), let svg = store.icons[id]?.svg {
                SVGIconView(svg: svg).padding(size * 0.16)
            } else {
                Image(systemName: SymbolCheck.safe(fallbackSymbol)).font(.system(size: size * 0.45))
            }
        case .symbol:
            Image(systemName: SymbolCheck.safe(icon!.value)).font(.system(size: size * 0.45))
        case nil:
            Image(systemName: SymbolCheck.safe(fallbackSymbol)).font(.system(size: size * 0.45))
        }
    }
}

// MARK: - Photos

/// A stored photo, loaded lazily (and fetched from the server if this device lacks it).
struct StoredImage: View {
    let fileId: String
    var maxPixel: CGFloat = 512
    var contentMode: ContentMode = .fit
    @Environment(HomeStore.self) private var store
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
            } else if failed {
                Image(systemName: "photo").foregroundStyle(.tertiary)
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .task(id: fileId) {
            if let hit = store.files.cachedImage(fileId, maxPixel: maxPixel) { image = hit; return }
            image = await store.files.image(fileId, maxPixel: maxPixel)
            failed = image == nil
        }
    }
}

/// Photo or icon for an item, at a given square size.
struct ItemThumbnail: View {
    let item: Item
    var size: CGFloat = 52
    @Environment(HomeStore.self) private var store

    var body: some View {
        let category = store.category(of: item)
        let tint = Color(hex: category?.colorHex ?? "#8E8E93")
        Group {
            if let photo = item.photoId {
                StoredImage(fileId: photo, maxPixel: size * 3, contentMode: .fit)
                    .padding(size * 0.06)
                    .frame(width: size, height: size)
                    .background(Color.photoBackground, in: RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
                    .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
            } else {
                IconTile(icon: item.icon, fallbackSymbol: category?.symbol ?? "shippingbox", tint: tint, size: size)
            }
        }
    }
}

struct Checkerboard: View {
    var body: some View {
        Canvas { ctx, size in
            let s: CGFloat = 10
            for y in stride(from: 0, to: size.height, by: s) {
                for x in stride(from: 0, to: size.width, by: s) where (Int(x / s) + Int(y / s)) % 2 == 0 {
                    ctx.fill(Path(CGRect(x: x, y: y, width: s, height: s)), with: .color(.gray.opacity(0.15)))
                }
            }
        }
    }
}

// MARK: - Small pieces

struct ColorDots: View {
    let colors: [String]
    var size: CGFloat = 14

    var body: some View {
        HStack(spacing: 4) {
            ForEach(colors, id: \.self) { hex in
                Circle().fill(Color(hex: hex)).frame(width: size, height: size)
                    .overlay(Circle().strokeBorder(.primary.opacity(0.15), lineWidth: 0.5))
            }
        }
    }
}

struct Pill: View {
    let text: String
    var color: Color = .secondary
    var systemImage: String?

    var body: some View {
        HStack(spacing: 3) {
            if let systemImage { Image(systemName: systemImage) }
            Text(text)
        }
        .font(.caption2.weight(.semibold))
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .foregroundStyle(color)
        .background(color.opacity(0.12), in: Capsule())
    }
}

/// Status pills for an item (expiry, low stock).
struct ItemBadges: View {
    let item: Item

    var body: some View {
        HStack(spacing: 4) {
            if item.isExpired {
                Pill(text: "已过期", color: .red, systemImage: "exclamationmark.triangle.fill")
            } else if item.isExpiringSoon, let d = item.expiresAt?.daysFromToday {
                Pill(text: d == 0 ? "今天到期" : "\(d) 天后到期", color: .orange, systemImage: "clock")
            }
            if item.isLowStock {
                Pill(text: "库存不足", color: .orange, systemImage: "arrow.down.circle")
            }
            if item.favorite {
                Image(systemName: "heart.fill").font(.caption2).foregroundStyle(.pink)
            }
        }
    }
}

/// Wraps children onto multiple lines.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            x += s.width + spacing
            maxX = max(maxX, x - spacing)
            rowHeight = max(rowHeight, s.height)
        }
        return CGSize(width: min(maxX, width), height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX && x + s.width > bounds.maxX { x = bounds.minX; y += rowHeight + spacing; rowHeight = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            rowHeight = max(rowHeight, s.height)
        }
    }
}

/// Free-text tags entered one at a time.
struct TagEditor: View {
    @Binding var tags: [String]
    @State private var draft = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !tags.isEmpty {
                FlowLayout {
                    ForEach(tags, id: \.self) { tag in
                        Button {
                            tags.removeAll { $0 == tag }
                        } label: {
                            HStack(spacing: 4) {
                                Text("#\(tag)")
                                Image(systemName: "xmark").font(.caption2)
                            }
                            .font(.subheadline)
                            .padding(.horizontal, 10).padding(.vertical, 5)
                            .background(Color.accentColor.opacity(0.12), in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("删除标签 \(tag)")
                    }
                }
            }
            HStack {
                TextField("添加标签，回车确认", text: $draft)
                    .onSubmit(add)
                    .submitLabel(.done)
                if !draft.trimmed.isEmpty {
                    Button("添加", action: add)
                }
            }
        }
    }

    private func add() {
        let parts = draft.split(whereSeparator: { ",，# ".contains($0) }).map { String($0).trimmed }.filter { !$0.isEmpty }
        for p in parts where !tags.contains(p) { tags.append(p) }
        draft = ""
    }
}

/// Number field for centimetre sizes with a unit suffix.
struct CentimetreField: View {
    let title: String
    @Binding var value: Double
    var range: ClosedRange<Double> = 5...5000

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            TextField(title, value: $value, format: .number.precision(.fractionLength(0)))
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 90)
                .onChange(of: value) { _, v in
                    let c = clamp(v, range.lowerBound, range.upperBound)
                    if c != v { value = c }
                }
            Text("cm").foregroundStyle(.secondary)
        }
    }
}

extension View {
    /// Standard rounded card background.
    func card(padding: CGFloat = 14) -> some View {
        self.padding(padding)
            .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
