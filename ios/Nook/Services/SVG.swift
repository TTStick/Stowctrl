import CoreGraphics
import Foundation

/// A deliberately small SVG reader for the line-art icons the server sends:
/// path / circle / ellipse / rect / line / polyline / polygon inside nested
/// groups, with inherited fill / stroke styling. Anything else is ignored.
struct SVGShape {
    var path: CGPath
    var fill: Bool
    var stroke: Bool
    var strokeWidth: CGFloat
    var lineCap: CGLineCap
    var lineJoin: CGLineJoin
    var evenOdd: Bool
    var opacity: CGFloat
}

struct SVGDocument {
    var viewBox: CGRect
    var shapes: [SVGShape]

    static func parse(_ text: String) -> SVGDocument? {
        guard let data = text.data(using: .utf8) else { return nil }
        let reader = SVGReader()
        let parser = XMLParser(data: data)
        parser.delegate = reader
        parser.shouldResolveExternalEntities = false
        guard parser.parse() || !reader.shapes.isEmpty, let viewBox = reader.viewBox ?? reader.fallbackBox else { return nil }
        guard !reader.shapes.isEmpty else { return nil }
        return SVGDocument(viewBox: viewBox, shapes: reader.shapes)
    }
}

private struct SVGStyle {
    var fill: String? = nil          // nil = inherit; default is painted
    var stroke: String? = nil        // default none
    var strokeWidth: CGFloat? = nil
    var lineCap: CGLineCap? = nil
    var lineJoin: CGLineJoin? = nil
    var evenOdd: Bool? = nil
    var opacity: CGFloat = 1

    func merged(with child: SVGStyle) -> SVGStyle {
        SVGStyle(fill: child.fill ?? fill, stroke: child.stroke ?? stroke,
                 strokeWidth: child.strokeWidth ?? strokeWidth, lineCap: child.lineCap ?? lineCap,
                 lineJoin: child.lineJoin ?? lineJoin, evenOdd: child.evenOdd ?? evenOdd,
                 opacity: opacity * child.opacity)
    }

    static func from(_ a: [String: String]) -> SVGStyle {
        var s = SVGStyle()
        s.fill = a["fill"]
        s.stroke = a["stroke"]
        s.strokeWidth = a["stroke-width"].flatMap(SVGReader.number)
        switch a["stroke-linecap"] {
        case "round": s.lineCap = .round
        case "square": s.lineCap = .square
        case "butt": s.lineCap = .butt
        default: break
        }
        switch a["stroke-linejoin"] {
        case "round": s.lineJoin = .round
        case "bevel": s.lineJoin = .bevel
        case "miter": s.lineJoin = .miter
        default: break
        }
        if let rule = a["fill-rule"] { s.evenOdd = rule == "evenodd" }
        if let o = a["opacity"].flatMap(SVGReader.number) { s.opacity = clamp(o, 0, 1) }
        return s
    }
}

private final class SVGReader: NSObject, XMLParserDelegate {
    var viewBox: CGRect?
    var fallbackBox: CGRect? = CGRect(x: 0, y: 0, width: 48, height: 48)
    var shapes: [SVGShape] = []
    private var stack: [SVGStyle] = [SVGStyle()]

    static func number(_ s: String) -> CGFloat? {
        var t = s.trimmingCharacters(in: .whitespaces)
        if t.hasSuffix("px") { t.removeLast(2) }
        return Double(t).map { CGFloat($0) }
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?,
                qualifiedName: String?, attributes a: [String: String] = [:]) {
        let tag = name.split(separator: ":").last.map(String.init) ?? name
        let style = stack.last!.merged(with: SVGStyle.from(a))
        func n(_ key: String) -> CGFloat { a[key].flatMap(Self.number) ?? 0 }

        var path: CGPath?
        switch tag {
        case "svg":
            if let vb = a["viewBox"] {
                let parts = vb.replacingOccurrences(of: ",", with: " ").split(separator: " ").compactMap { Double($0) }
                if parts.count == 4, parts[2] > 0, parts[3] > 0 {
                    viewBox = CGRect(x: parts[0], y: parts[1], width: parts[2], height: parts[3])
                }
            }
            if viewBox == nil, n("width") > 0, n("height") > 0 {
                fallbackBox = CGRect(x: 0, y: 0, width: n("width"), height: n("height"))
            }
        case "path":
            path = a["d"].flatMap(SVGPathParser.parse)
        case "circle":
            let r = n("r")
            if r > 0 { path = CGPath(ellipseIn: CGRect(x: n("cx") - r, y: n("cy") - r, width: r * 2, height: r * 2), transform: nil) }
        case "ellipse":
            let rx = n("rx"), ry = n("ry")
            if rx > 0, ry > 0 { path = CGPath(ellipseIn: CGRect(x: n("cx") - rx, y: n("cy") - ry, width: rx * 2, height: ry * 2), transform: nil) }
        case "rect":
            let w = n("width"), h = n("height")
            if w > 0, h > 0 {
                var rx = a["rx"].flatMap(Self.number), ry = a["ry"].flatMap(Self.number)
                if rx == nil { rx = ry }
                if ry == nil { ry = rx }
                let cw = min(rx ?? 0, w / 2), ch = min(ry ?? 0, h / 2)
                path = CGPath(roundedRect: CGRect(x: n("x"), y: n("y"), width: w, height: h),
                              cornerWidth: max(0, cw), cornerHeight: max(0, ch), transform: nil)
            }
        case "line":
            let p = CGMutablePath()
            p.move(to: CGPoint(x: n("x1"), y: n("y1")))
            p.addLine(to: CGPoint(x: n("x2"), y: n("y2")))
            path = p
        case "polyline", "polygon":
            let nums = SVGPathParser.numbers(a["points"] ?? "")
            if nums.count >= 4 {
                let p = CGMutablePath()
                p.move(to: CGPoint(x: nums[0], y: nums[1]))
                var i = 2
                while i + 1 < nums.count { p.addLine(to: CGPoint(x: nums[i], y: nums[i + 1])); i += 2 }
                if tag == "polygon" { p.closeSubpath() }
                path = p
            }
        default:
            break
        }

        stack.append(style)
        guard let path, !path.isEmpty else { return }
        let fillValue = style.fill ?? "currentColor"
        let strokeValue = style.stroke ?? "none"
        let shape = SVGShape(path: path, fill: fillValue != "none", stroke: strokeValue != "none",
                             strokeWidth: style.strokeWidth ?? 1, lineCap: style.lineCap ?? .butt,
                             lineJoin: style.lineJoin ?? .miter, evenOdd: style.evenOdd ?? false,
                             opacity: style.opacity)
        if shape.fill || shape.stroke { shapes.append(shape) }
    }

    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        if stack.count > 1 { stack.removeLast() }
    }
}

/// SVG path data ("M10 10 L20 20 A5 5 0 0 1 30 30 Z") → CGPath.
enum SVGPathParser {
    static func numbers(_ s: String) -> [CGFloat] {
        var scanner = Scanner(bytes: Array(s.utf8))
        var out: [CGFloat] = []
        while let n = scanner.number() { out.append(n) }
        return out
    }

    static func parse(_ d: String) -> CGPath? {
        var s = Scanner(bytes: Array(d.utf8))
        let path = CGMutablePath()
        var current = CGPoint.zero
        var start = CGPoint.zero
        var lastControl: CGPoint?
        var lastCmd: UInt8 = 0
        var cmd: UInt8 = 0
        var hasPoint = false

        func ensureStart() {
            if !hasPoint { path.move(to: current); hasPoint = true; start = current }
        }

        while true {
            s.skipSeparators()
            guard let c = s.peek() else { break }
            if Scanner.isCommand(c) {
                cmd = c
                s.advance()
            } else if cmd == 0 || cmd == UInt8(ascii: "Z") || cmd == UInt8(ascii: "z") {
                break  // numbers with no command to apply them to
            }
            let rel = cmd >= UInt8(ascii: "a")
            let upper = rel ? cmd - 32 : cmd
            func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { rel ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y) }

            switch upper {
            case UInt8(ascii: "M"):
                guard let x = s.number(), let y = s.number() else { return finish(path) }
                current = pt(x, y)
                path.move(to: current)
                start = current
                hasPoint = true
                lastControl = nil
                cmd = rel ? UInt8(ascii: "l") : UInt8(ascii: "L")  // following pairs are line-tos
            case UInt8(ascii: "L"):
                guard let x = s.number(), let y = s.number() else { return finish(path) }
                ensureStart()
                current = pt(x, y)
                path.addLine(to: current)
                lastControl = nil
            case UInt8(ascii: "H"):
                guard let x = s.number() else { return finish(path) }
                ensureStart()
                current = CGPoint(x: rel ? current.x + x : x, y: current.y)
                path.addLine(to: current)
                lastControl = nil
            case UInt8(ascii: "V"):
                guard let y = s.number() else { return finish(path) }
                ensureStart()
                current = CGPoint(x: current.x, y: rel ? current.y + y : y)
                path.addLine(to: current)
                lastControl = nil
            case UInt8(ascii: "C"):
                guard let x1 = s.number(), let y1 = s.number(), let x2 = s.number(), let y2 = s.number(),
                      let x = s.number(), let y = s.number() else { return finish(path) }
                ensureStart()
                let c1 = pt(x1, y1), c2 = pt(x2, y2), end = pt(x, y)
                path.addCurve(to: end, control1: c1, control2: c2)
                lastControl = c2
                current = end
            case UInt8(ascii: "S"):
                guard let x2 = s.number(), let y2 = s.number(), let x = s.number(), let y = s.number() else { return finish(path) }
                ensureStart()
                let prevCubic = [UInt8(ascii: "C"), UInt8(ascii: "S")].contains(lastCmd & ~32)
                let c1 = prevCubic && lastControl != nil
                    ? CGPoint(x: 2 * current.x - lastControl!.x, y: 2 * current.y - lastControl!.y) : current
                let c2 = pt(x2, y2), end = pt(x, y)
                path.addCurve(to: end, control1: c1, control2: c2)
                lastControl = c2
                current = end
            case UInt8(ascii: "Q"):
                guard let x1 = s.number(), let y1 = s.number(), let x = s.number(), let y = s.number() else { return finish(path) }
                ensureStart()
                let c = pt(x1, y1), end = pt(x, y)
                path.addQuadCurve(to: end, control: c)
                lastControl = c
                current = end
            case UInt8(ascii: "T"):
                guard let x = s.number(), let y = s.number() else { return finish(path) }
                ensureStart()
                let prevQuad = [UInt8(ascii: "Q"), UInt8(ascii: "T")].contains(lastCmd & ~32)
                let c = prevQuad && lastControl != nil
                    ? CGPoint(x: 2 * current.x - lastControl!.x, y: 2 * current.y - lastControl!.y) : current
                let end = pt(x, y)
                path.addQuadCurve(to: end, control: c)
                lastControl = c
                current = end
            case UInt8(ascii: "A"):
                guard let rx = s.number(), let ry = s.number(), let rot = s.number(),
                      let large = s.flag(), let sweep = s.flag(), let x = s.number(), let y = s.number() else { return finish(path) }
                ensureStart()
                let end = pt(x, y)
                addArc(path, from: current, rx: rx, ry: ry, angle: rot, large: large, sweep: sweep, to: end)
                current = end
                lastControl = nil
            case UInt8(ascii: "Z"):
                if hasPoint { path.closeSubpath() }
                current = start
                lastControl = nil
            default:
                return finish(path)
            }
            lastCmd = upper
        }
        return finish(path)
    }

    private static func finish(_ p: CGMutablePath) -> CGPath? { p.isEmpty ? nil : p }

    /// Elliptical arc via the SVG spec's centre parameterisation, as cubic Béziers.
    static func addArc(_ path: CGMutablePath, from p0: CGPoint, rx rxIn: CGFloat, ry ryIn: CGFloat,
                       angle: CGFloat, large: Bool, sweep: Bool, to p: CGPoint) {
        if p0 == p { return }
        var rx = abs(rxIn), ry = abs(ryIn)
        if rx == 0 || ry == 0 { path.addLine(to: p); return }
        let phi = angle * .pi / 180
        let cosP = cos(phi), sinP = sin(phi)
        let dx2 = (p0.x - p.x) / 2, dy2 = (p0.y - p.y) / 2
        let x1p = cosP * dx2 + sinP * dy2
        let y1p = -sinP * dx2 + cosP * dy2
        let lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if lambda > 1 { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        let num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        let den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = den == 0 ? 0 : sqrt(max(0, num / den))
        if large == sweep { coef = -coef }
        let cxp = coef * rx * y1p / ry
        let cyp = coef * -ry * x1p / rx
        let cx = cosP * cxp - sinP * cyp + (p0.x + p.x) / 2
        let cy = sinP * cxp + cosP * cyp + (p0.y + p.y) / 2

        func ang(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        }
        let ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry
        let vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry
        let theta1 = ang(1, 0, ux, uy)
        var delta = ang(ux, uy, vx, vy)
        if !sweep && delta > 0 { delta -= 2 * .pi } else if sweep && delta < 0 { delta += 2 * .pi }

        let segments = max(1, Int(ceil(abs(delta) / (.pi / 2) - 1e-7)))
        let step = delta / CGFloat(segments)
        let t = 4.0 / 3.0 * tan(step / 4)
        func map(_ u: CGFloat, _ v: CGFloat) -> CGPoint {
            CGPoint(x: cx + rx * cosP * u - ry * sinP * v, y: cy + rx * sinP * u + ry * cosP * v)
        }
        var a1 = theta1
        for i in 0..<segments {
            let a2 = a1 + step
            let c1 = map(cos(a1) - t * sin(a1), sin(a1) + t * cos(a1))
            let c2 = map(cos(a2) + t * sin(a2), sin(a2) - t * cos(a2))
            let end = i == segments - 1 ? p : map(cos(a2), sin(a2))
            path.addCurve(to: end, control1: c1, control2: c2)
            a1 = a2
        }
    }

    struct Scanner {
        let bytes: [UInt8]
        var i = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        static func isCommand(_ c: UInt8) -> Bool {
            switch c {
            case UInt8(ascii: "M"), UInt8(ascii: "m"), UInt8(ascii: "L"), UInt8(ascii: "l"),
                 UInt8(ascii: "H"), UInt8(ascii: "h"), UInt8(ascii: "V"), UInt8(ascii: "v"),
                 UInt8(ascii: "C"), UInt8(ascii: "c"), UInt8(ascii: "S"), UInt8(ascii: "s"),
                 UInt8(ascii: "Q"), UInt8(ascii: "q"), UInt8(ascii: "T"), UInt8(ascii: "t"),
                 UInt8(ascii: "A"), UInt8(ascii: "a"), UInt8(ascii: "Z"), UInt8(ascii: "z"):
                return true
            default:
                return false
            }
        }

        func peek() -> UInt8? { i < bytes.count ? bytes[i] : nil }
        mutating func advance() { i += 1 }

        mutating func skipSeparators() {
            while i < bytes.count, [32, 9, 10, 13, 44].contains(bytes[i]) { i += 1 }
        }

        /// Arc flags are a single 0/1 and may be written without separators ("a5 5 0 01 10 10").
        mutating func flag() -> Bool? {
            skipSeparators()
            guard let c = peek(), c == UInt8(ascii: "0") || c == UInt8(ascii: "1") else { return nil }
            i += 1
            return c == UInt8(ascii: "1")
        }

        mutating func number() -> CGFloat? {
            skipSeparators()
            let startIndex = i
            if i < bytes.count, bytes[i] == UInt8(ascii: "-") || bytes[i] == UInt8(ascii: "+") { i += 1 }
            var digits = 0
            while i < bytes.count, bytes[i] >= 48, bytes[i] <= 57 { i += 1; digits += 1 }
            if i < bytes.count, bytes[i] == UInt8(ascii: ".") {
                i += 1
                while i < bytes.count, bytes[i] >= 48, bytes[i] <= 57 { i += 1; digits += 1 }
            }
            guard digits > 0 else { i = startIndex; return nil }
            if i < bytes.count, bytes[i] == UInt8(ascii: "e") || bytes[i] == UInt8(ascii: "E") {
                let save = i
                i += 1
                if i < bytes.count, bytes[i] == UInt8(ascii: "-") || bytes[i] == UInt8(ascii: "+") { i += 1 }
                var expDigits = 0
                while i < bytes.count, bytes[i] >= 48, bytes[i] <= 57 { i += 1; expDigits += 1 }
                if expDigits == 0 { i = save }
            }
            guard let str = String(bytes: bytes[startIndex..<i], encoding: .ascii), let v = Double(str) else { return nil }
            return CGFloat(v)
        }
    }
}
