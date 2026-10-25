import SwiftUI

/// Top-down drawings of furniture for the floor plan. Everything is drawn
/// unrotated in a `w × d` box with the front edge at the bottom; callers
/// rotate the context first.
enum FurnitureArt {
    struct Palette {
        var fill: Color
        var stroke: Color
        var detail: Color { stroke.opacity(0.55) }
        var soft: Color { stroke.opacity(0.18) }
    }

    static func palette(for f: Furniture) -> Palette {
        let kind = Catalog.kind(f.kind)
        if let hex = f.colorHex {
            let ui = UIColor(hex: hex)
            return Palette(fill: Color(uiColor: ui), stroke: Color(uiColor: ui.darker(0.35)))
        }
        return Palette(fill: kind.material.fill, stroke: kind.material.stroke)
    }

    /// Draws `f` filling `size` (its on-screen footprint), handling rotation.
    static func draw(_ f: Furniture, in ctx: GraphicsContext, size: CGSize) {
        let kind = Catalog.kind(f.kind)
        let quarter = f.rotation % 2 == 1
        let art = quarter ? CGSize(width: size.height, height: size.width) : size
        var c = ctx
        c.translateBy(x: size.width / 2, y: size.height / 2)
        c.rotate(by: .degrees(Double(f.rotation) * 90))
        c.translateBy(x: -art.width / 2, y: -art.height / 2)
        let style = f.kind == Catalog.customKindId ? FurnitureStyle.cabinet : kind.style
        drawArt(style, in: c, rect: CGRect(origin: .zero, size: art), palette: palette(for: f),
                overhead: kind.layer == .wall)
    }

    private static func rr(_ r: CGRect, _ radius: CGFloat) -> Path {
        Path(roundedRect: r, cornerRadius: max(0, min(radius, min(r.width, r.height) / 2)), style: .continuous)
    }

    static func drawArt(_ style: FurnitureStyle, in c: GraphicsContext, rect r: CGRect, palette p: Palette, overhead: Bool) {
        let m = min(r.width, r.height)
        let lw = clamp(m * 0.035, 0.6, 1.6)
        let corner = clamp(m * 0.08, 1, 6)
        let body = rr(r.insetBy(dx: lw / 2, dy: lw / 2), corner)

        func base(_ fill: Color? = nil) {
            c.fill(body, with: .color(fill ?? p.fill))
            if overhead {
                c.stroke(body, with: .color(p.stroke), style: StrokeStyle(lineWidth: lw, dash: [3, 2]))
            } else {
                c.stroke(body, with: .color(p.stroke), lineWidth: lw)
            }
        }
        func line(_ a: CGPoint, _ b: CGPoint, _ color: Color? = nil, _ width: CGFloat? = nil, dash: [CGFloat] = []) {
            var path = Path()
            path.move(to: a)
            path.addLine(to: b)
            c.stroke(path, with: .color(color ?? p.detail), style: StrokeStyle(lineWidth: width ?? lw, lineCap: .round, dash: dash))
        }
        func dot(_ center: CGPoint, _ radius: CGFloat, _ color: Color? = nil) {
            c.fill(Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)),
                   with: .color(color ?? p.detail))
        }
        func frontStrip(_ fraction: CGFloat = 0.14) -> CGRect {
            let h = max(lw * 2, r.height * fraction)
            return CGRect(x: r.minX + lw, y: r.maxY - h - lw / 2, width: r.width - lw * 2, height: h)
        }

        switch style {
        case .cabinet:
            base()
            let s = frontStrip()
            c.fill(Path(s), with: .color(p.soft))
            let doors = r.width > r.height * 2.4 ? 3 : (r.width > r.height * 1.1 ? 2 : 1)
            for i in 1..<max(doors, 1) {
                let x = r.minX + r.width * CGFloat(i) / CGFloat(doors)
                line(CGPoint(x: x, y: s.minY), CGPoint(x: x, y: s.maxY))
            }
            for i in 0..<doors {
                let x = r.minX + r.width * (CGFloat(i) + 0.5) / CGFloat(doors)
                dot(CGPoint(x: x, y: s.midY), max(0.8, lw))
            }

        case .wardrobe:
            base()
            let s = frontStrip(0.12)
            c.fill(Path(s), with: .color(p.soft))
            let rodY = r.minY + (s.minY - r.minY) * 0.5
            line(CGPoint(x: r.minX + r.width * 0.06, y: rodY), CGPoint(x: r.maxX - r.width * 0.06, y: rodY), p.stroke)
            let hangers = max(2, Int(r.width / max(m * 0.5, 4)))
            for i in 0..<hangers {
                let x = r.minX + r.width * (CGFloat(i) + 0.5) / CGFloat(hangers)
                line(CGPoint(x: x, y: rodY - m * 0.18), CGPoint(x: x, y: rodY + m * 0.18), p.detail, lw * 0.8)
            }
            let doors = r.width > r.height * 2 ? 3 : 2
            for i in 1..<doors {
                let x = r.minX + r.width * CGFloat(i) / CGFloat(doors)
                line(CGPoint(x: x, y: s.minY), CGPoint(x: x, y: s.maxY))
            }

        case .drawers:
            base()
            let s = frontStrip(0.16)
            c.fill(Path(s), with: .color(p.soft))
            line(CGPoint(x: s.midX - s.width * 0.18, y: s.midY), CGPoint(x: s.midX + s.width * 0.18, y: s.midY), p.stroke, lw * 1.4)

        case .shelf:
            base(p.fill.opacity(0.75))
            let rows = r.height > r.width * 0.8 ? 3 : 2
            for i in 1...rows {
                let y = r.minY + r.height * CGFloat(i) / CGFloat(rows + 1)
                line(CGPoint(x: r.minX + lw * 2, y: y), CGPoint(x: r.maxX - lw * 2, y: y), p.detail, lw, dash: [2, 2])
            }

        case .bed:
            base()
            let inset = m * 0.05
            let mattress = r.insetBy(dx: inset, dy: inset)
            c.fill(rr(mattress, corner), with: .color(.adaptive("#FBFAF7", "#4A4C50")))
            let pillowH = mattress.height * 0.16
            let pillows = r.width / r.height > 0.75 ? 2 : 1
            let gap = mattress.width * 0.05
            let pw = (mattress.width - gap * CGFloat(pillows + 1)) / CGFloat(pillows)
            for i in 0..<pillows {
                let pr = CGRect(x: mattress.minX + gap + CGFloat(i) * (pw + gap), y: mattress.minY + gap * 0.8, width: pw, height: pillowH)
                c.fill(rr(pr, pillowH * 0.4), with: .color(.adaptive("#FFFFFF", "#5C5F64")))
                c.stroke(rr(pr, pillowH * 0.4), with: .color(p.detail), lineWidth: lw * 0.8)
            }
            let blanket = CGRect(x: mattress.minX, y: mattress.minY + mattress.height * 0.32,
                                 width: mattress.width, height: mattress.height * 0.68)
            c.fill(rr(blanket, corner), with: .color(Material.fabric.fill))
            line(CGPoint(x: blanket.minX, y: blanket.minY + blanket.height * 0.12),
                 CGPoint(x: blanket.maxX, y: blanket.minY + blanket.height * 0.12), Material.fabric.stroke, lw)

        case .sofa, .armchair:
            base()
            let back = r.height * 0.26
            let arm = min(r.width * 0.13, r.height * 0.24)
            c.fill(rr(CGRect(x: r.minX, y: r.minY, width: r.width, height: back), corner), with: .color(p.stroke.opacity(0.35)))
            c.fill(rr(CGRect(x: r.minX, y: r.minY, width: arm, height: r.height), corner), with: .color(p.stroke.opacity(0.35)))
            c.fill(rr(CGRect(x: r.maxX - arm, y: r.minY, width: arm, height: r.height), corner), with: .color(p.stroke.opacity(0.35)))
            let seat = CGRect(x: r.minX + arm, y: r.minY + back, width: r.width - arm * 2, height: r.height - back - lw)
            let cushions = style == .armchair ? 1 : (seat.width / seat.height > 2.2 ? 3 : 2)
            let cw = seat.width / CGFloat(cushions)
            for i in 0..<cushions {
                let cr = CGRect(x: seat.minX + CGFloat(i) * cw, y: seat.minY, width: cw, height: seat.height).insetBy(dx: lw, dy: lw)
                c.stroke(rr(cr, corner), with: .color(p.detail), lineWidth: lw * 0.8)
            }

        case .table:
            base()
            c.stroke(rr(r.insetBy(dx: m * 0.1, dy: m * 0.1), corner), with: .color(p.soft), lineWidth: lw)

        case .dining:
            let chairD = r.height * 0.18
            let table = CGRect(x: r.minX + r.width * 0.06, y: r.minY + chairD + lw, width: r.width * 0.88, height: r.height - chairD * 2 - lw * 2)
            let n = clamp(Int(table.width / max(chairD * 1.6, 1)), 1, 4)
            let cw = min(table.width / CGFloat(n) * 0.62, chairD * 1.3)
            for i in 0..<n {
                let cx = table.minX + table.width * (CGFloat(i) + 0.5) / CGFloat(n)
                for top in [true, false] {
                    let cr = CGRect(x: cx - cw / 2, y: top ? r.minY + lw : table.maxY + lw * 0.5, width: cw, height: chairD - lw)
                    c.fill(rr(cr, corner), with: .color(p.fill.opacity(0.8)))
                    c.stroke(rr(cr, corner), with: .color(p.stroke), lineWidth: lw * 0.8)
                }
            }
            let tp = rr(table, corner)
            c.fill(tp, with: .color(p.fill))
            c.stroke(tp, with: .color(p.stroke), lineWidth: lw)

        case .desk:
            base()
            let mon = CGRect(x: r.midX - r.width * 0.2, y: r.minY + r.height * 0.12, width: r.width * 0.4, height: max(lw * 2, r.height * 0.08))
            c.fill(rr(mon, 1), with: .color(Material.dark.fill))
            c.stroke(rr(r.insetBy(dx: m * 0.1, dy: m * 0.1), corner), with: .color(p.soft), lineWidth: lw)

        case .chair:
            let seat = r.insetBy(dx: r.width * 0.08, dy: r.height * 0.08)
            c.fill(rr(seat, corner * 1.5), with: .color(p.fill))
            c.stroke(rr(seat, corner * 1.5), with: .color(p.stroke), lineWidth: lw)
            let back = CGRect(x: seat.minX, y: seat.minY, width: seat.width, height: seat.height * 0.24)
            c.fill(rr(back, corner), with: .color(p.stroke.opacity(0.4)))

        case .tv:
            base()
            line(CGPoint(x: r.minX + r.width * 0.05, y: r.maxY - lw * 1.5), CGPoint(x: r.maxX - r.width * 0.05, y: r.maxY - lw * 1.5),
                 .adaptive("#7FB5D9", "#5D8FB0"), lw)

        case .fridge:
            base()
            let s = frontStrip(0.12)
            c.fill(Path(s), with: .color(p.soft))
            line(CGPoint(x: r.midX, y: s.minY), CGPoint(x: r.midX, y: s.maxY))
            line(CGPoint(x: r.midX - r.width * 0.12, y: s.minY - lw * 1.5), CGPoint(x: r.midX - r.width * 0.04, y: s.minY - lw * 1.5), p.stroke, lw * 1.3)
            line(CGPoint(x: r.midX + r.width * 0.04, y: s.minY - lw * 1.5), CGPoint(x: r.midX + r.width * 0.12, y: s.minY - lw * 1.5), p.stroke, lw * 1.3)

        case .stove:
            base()
            let rad = min(r.width / 4, r.height / 4) * 0.62
            for (fx, fy) in [(0.28, 0.3), (0.72, 0.3), (0.28, 0.7), (0.72, 0.7)] {
                let center = CGPoint(x: r.minX + r.width * fx, y: r.minY + r.height * fy)
                let outer = Path(ellipseIn: CGRect(x: center.x - rad, y: center.y - rad, width: rad * 2, height: rad * 2))
                c.stroke(outer, with: .color(Material.dark.fill), lineWidth: lw * 1.2)
                dot(center, rad * 0.35, Material.dark.fill)
            }

        case .sink:
            base()
            let basin = CGRect(x: r.minX + r.width * 0.18, y: r.minY + r.height * 0.22, width: r.width * 0.64, height: r.height * 0.56)
            c.fill(rr(basin, corner * 2), with: .color(.adaptive("#E3EEF4", "#3F4D56")))
            c.stroke(rr(basin, corner * 2), with: .color(p.stroke), lineWidth: lw)
            dot(CGPoint(x: basin.midX, y: basin.midY), max(0.8, m * 0.03), p.stroke)
            dot(CGPoint(x: r.midX, y: r.minY + r.height * 0.12), max(1, m * 0.05), p.stroke)

        case .toilet:
            let tank = CGRect(x: r.minX + r.width * 0.05, y: r.minY, width: r.width * 0.9, height: r.height * 0.28)
            c.fill(rr(tank, corner), with: .color(p.fill))
            c.stroke(rr(tank, corner), with: .color(p.stroke), lineWidth: lw)
            let bowl = CGRect(x: r.minX + r.width * 0.1, y: r.minY + r.height * 0.24, width: r.width * 0.8, height: r.height * 0.74)
            c.fill(Path(ellipseIn: bowl), with: .color(p.fill))
            c.stroke(Path(ellipseIn: bowl), with: .color(p.stroke), lineWidth: lw)
            c.stroke(Path(ellipseIn: bowl.insetBy(dx: bowl.width * 0.18, dy: bowl.height * 0.16)), with: .color(p.detail), lineWidth: lw * 0.8)

        case .bathtub:
            c.fill(rr(r, corner * 2), with: .color(p.fill))
            c.stroke(rr(r, corner * 2), with: .color(p.stroke), lineWidth: lw)
            let inner = r.insetBy(dx: m * 0.12, dy: m * 0.12)
            c.fill(rr(inner, m * 0.3), with: .color(.adaptive("#DCEBF4", "#3E4F5A")))
            c.stroke(rr(inner, m * 0.3), with: .color(p.detail), lineWidth: lw)
            dot(CGPoint(x: inner.minX + inner.width * 0.12, y: inner.midY), max(0.8, m * 0.04), p.stroke)

        case .shower:
            base()
            line(CGPoint(x: r.minX + lw, y: r.minY + lw), CGPoint(x: r.maxX - lw, y: r.maxY - lw), p.soft)
            line(CGPoint(x: r.maxX - lw, y: r.minY + lw), CGPoint(x: r.minX + lw, y: r.maxY - lw), p.soft)
            c.stroke(Path(ellipseIn: CGRect(x: r.midX - m * 0.08, y: r.midY - m * 0.08, width: m * 0.16, height: m * 0.16)),
                     with: .color(p.stroke), lineWidth: lw)

        case .washer:
            base()
            let rad = m * 0.32
            let center = CGPoint(x: r.midX, y: r.midY + m * 0.06)
            let drum = Path(ellipseIn: CGRect(x: center.x - rad, y: center.y - rad, width: rad * 2, height: rad * 2))
            c.fill(drum, with: .color(.adaptive("#DDE7EE", "#46525A")))
            c.stroke(drum, with: .color(p.stroke), lineWidth: lw * 1.2)
            c.stroke(Path(ellipseIn: CGRect(x: center.x - rad * 0.6, y: center.y - rad * 0.6, width: rad * 1.2, height: rad * 1.2)),
                     with: .color(p.detail), lineWidth: lw * 0.8)
            line(CGPoint(x: r.minX + r.width * 0.15, y: r.minY + r.height * 0.11), CGPoint(x: r.minX + r.width * 0.45, y: r.minY + r.height * 0.11), p.detail)

        case .appliance:
            base()
            let s = frontStrip(0.16)
            c.fill(Path(s), with: .color(p.soft))
            let panel = CGRect(x: r.minX + r.width * 0.12, y: r.minY + r.height * 0.18, width: r.width * 0.76, height: r.height * 0.5)
            c.stroke(rr(panel, corner), with: .color(p.detail), lineWidth: lw)
            dot(CGPoint(x: s.maxX - s.width * 0.12, y: s.midY), max(0.8, lw))

        case .door:
            let wall = CGRect(x: r.minX, y: r.minY, width: r.width, height: max(lw * 2, r.height * 0.06))
            c.fill(Path(wall), with: .color(Color.wallColor.opacity(0.35)))
            var arc = Path()
            arc.addArc(center: CGPoint(x: r.minX, y: r.minY), radius: min(r.width, r.height) * 0.98,
                       startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
            c.stroke(arc, with: .color(p.detail), style: StrokeStyle(lineWidth: lw, dash: [3, 2]))
            line(CGPoint(x: r.minX + lw, y: r.minY), CGPoint(x: r.minX + lw, y: r.minY + min(r.width, r.height) * 0.98), p.stroke, lw * 2)

        case .window:
            c.fill(Path(r), with: .color(p.fill))
            c.stroke(Path(r), with: .color(p.stroke), lineWidth: lw)
            line(CGPoint(x: r.minX, y: r.midY), CGPoint(x: r.maxX, y: r.midY), p.stroke, lw * 0.8)

        case .wall, .pillar:
            c.fill(Path(r), with: .color(Color.wallColor))

        case .plant:
            let pot = r.insetBy(dx: r.width * 0.22, dy: r.height * 0.22)
            c.fill(Path(ellipseIn: pot), with: .color(Material.cardboard.fill))
            for (fx, fy, s) in [(0.35, 0.35, 0.34), (0.65, 0.38, 0.32), (0.5, 0.62, 0.36), (0.3, 0.62, 0.26), (0.7, 0.66, 0.26)] {
                let rad = m * CGFloat(s) * 0.55
                let center = CGPoint(x: r.minX + r.width * fx, y: r.minY + r.height * fy)
                let leaf = Path(ellipseIn: CGRect(x: center.x - rad, y: center.y - rad, width: rad * 2, height: rad * 2))
                c.fill(leaf, with: .color(p.fill.opacity(0.9)))
                c.stroke(leaf, with: .color(p.stroke), lineWidth: lw * 0.7)
            }

        case .rug:
            c.fill(rr(r, corner), with: .color(p.fill.opacity(0.7)))
            c.stroke(rr(r.insetBy(dx: m * 0.08, dy: m * 0.08), corner), with: .color(p.stroke.opacity(0.6)),
                     style: StrokeStyle(lineWidth: lw, dash: [4, 3]))
            let tassels = max(4, Int(r.height / 6))
            for i in 0..<tassels {
                let y = r.minY + r.height * (CGFloat(i) + 0.5) / CGFloat(tassels)
                line(CGPoint(x: r.minX - m * 0.02, y: y), CGPoint(x: r.minX + m * 0.03, y: y), p.detail, lw * 0.6)
                line(CGPoint(x: r.maxX - m * 0.03, y: y), CGPoint(x: r.maxX + m * 0.02, y: y), p.detail, lw * 0.6)
            }

        case .lamp:
            let shade = r.insetBy(dx: r.width * 0.12, dy: r.height * 0.12)
            c.fill(Path(ellipseIn: shade), with: .color(.adaptive("#FFF4CF", "#6B6040")))
            c.stroke(Path(ellipseIn: shade), with: .color(p.stroke), lineWidth: lw)
            dot(CGPoint(x: r.midX, y: r.midY), m * 0.08, p.stroke)

        case .box:
            base()
            line(CGPoint(x: r.midX, y: r.minY + lw), CGPoint(x: r.midX, y: r.maxY - lw), p.detail, lw * 1.4)
            line(CGPoint(x: r.minX + lw, y: r.midY), CGPoint(x: r.maxX - lw, y: r.midY), p.soft, lw)

        case .basket:
            base()
            let step = max(3, m * 0.18)
            var x = r.minX + step
            while x < r.maxX {
                line(CGPoint(x: x, y: r.minY + lw * 2), CGPoint(x: x, y: r.maxY - lw * 2), p.soft, lw * 0.8)
                x += step
            }

        case .ac:
            base()
            for f in [0.35, 0.55, 0.75] {
                let y = r.minY + r.height * CGFloat(f)
                line(CGPoint(x: r.minX + r.width * 0.08, y: y), CGPoint(x: r.maxX - r.width * 0.08, y: y), p.detail, lw * 0.8)
            }

        case .generic:
            base()
        }
    }
}

extension UIColor {
    func darker(_ amount: CGFloat) -> UIColor {
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        return UIColor(hue: h, saturation: min(1, s * 1.1), brightness: max(0, b * (1 - amount)), alpha: a)
    }
}
