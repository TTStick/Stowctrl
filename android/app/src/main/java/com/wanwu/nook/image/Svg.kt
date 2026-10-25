package com.wanwu.nook.image

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * A small SVG reader for the line-art icons the server sends (same subset
 * as the iOS SVG.swift): path / circle / ellipse / rect / line / polyline /
 * polygon in nested groups with inherited fill and stroke. No Android types,
 * so it's unit-testable; the UI turns the ops into a Compose Path.
 */

sealed interface PathOp {
    data class MoveTo(val x: Float, val y: Float) : PathOp
    data class LineTo(val x: Float, val y: Float) : PathOp
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathOp
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathOp
    data object Close : PathOp
}

data class SvgShape(
    val ops: List<PathOp>,
    val fill: Boolean,
    val stroke: Boolean,
    val strokeWidth: Float,
    val lineCap: String,
    val lineJoin: String,
    val evenOdd: Boolean,
    val opacity: Float,
)

data class SvgDocument(val minX: Float, val minY: Float, val width: Float, val height: Float, val shapes: List<SvgShape>) {
    companion object {
        fun parse(text: String): SvgDocument? = runCatching { SvgReader.read(text) }.getOrNull()
    }
}

private data class Style(
    val fill: String? = null, val stroke: String? = null, val strokeWidth: Float? = null,
    val cap: String? = null, val join: String? = null, val evenOdd: Boolean? = null, val opacity: Float = 1f,
) {
    fun merged(c: Style) = Style(c.fill ?: fill, c.stroke ?: stroke, c.strokeWidth ?: strokeWidth, c.cap ?: cap,
        c.join ?: join, c.evenOdd ?: evenOdd, opacity * c.opacity)

    companion object {
        fun of(e: Element): Style {
            fun a(n: String) = e.getAttribute(n).takeIf { it.isNotEmpty() }
            return Style(
                fill = a("fill"), stroke = a("stroke"), strokeWidth = a("stroke-width")?.let(SvgReader::number),
                cap = a("stroke-linecap")?.takeIf { it in setOf("round", "square", "butt") },
                join = a("stroke-linejoin")?.takeIf { it in setOf("round", "bevel", "miter") },
                evenOdd = a("fill-rule")?.let { it == "evenodd" },
                opacity = a("opacity")?.let(SvgReader::number)?.coerceIn(0f, 1f) ?: 1f,
            )
        }
    }
}

private object SvgReader {
    fun number(s: String): Float? = s.trim().removeSuffix("px").toFloatOrNull()

    fun read(text: String): SvgDocument? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { isExpandEntityReferences = false }
        }
        val root = factory.newDocumentBuilder().parse(text.byteInputStream()).documentElement
        if (local(root.tagName) != "svg") return null
        val vb = root.getAttribute("viewBox").replace(",", " ").split(" ").filter { it.isNotBlank() }.mapNotNull { it.toFloatOrNull() }
        val box = if (vb.size == 4 && vb[2] > 0 && vb[3] > 0) vb else {
            val w = number(root.getAttribute("width")) ?: 48f
            val h = number(root.getAttribute("height")) ?: 48f
            listOf(0f, 0f, w, h)
        }
        val shapes = mutableListOf<SvgShape>()
        walk(root, Style.of(root), shapes, 0)
        if (shapes.isEmpty()) return null
        return SvgDocument(box[0], box[1], box[2], box[3], shapes)
    }

    private fun local(tag: String) = tag.substringAfter(':')

    private fun walk(parent: Element, style: Style, out: MutableList<SvgShape>, depth: Int) {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            val tag = local(e.tagName)
            val s = style.merged(Style.of(e))
            if (tag == "g") {
                if (depth < 6) walk(e, s, out, depth + 1)
                continue
            }
            val ops = geometry(tag, e) ?: continue
            if (ops.isEmpty()) continue
            val fill = (s.fill ?: "currentColor") != "none"
            val stroke = (s.stroke ?: "none") != "none"
            if (!fill && !stroke) continue
            out += SvgShape(ops, fill, stroke, s.strokeWidth ?: 1f, s.cap ?: "butt", s.join ?: "miter", s.evenOdd ?: false, s.opacity)
        }
    }

    private fun geometry(tag: String, e: Element): List<PathOp>? {
        fun n(k: String) = number(e.getAttribute(k)) ?: 0f
        return when (tag) {
            "path" -> SvgPath.parse(e.getAttribute("d"))
            "circle" -> n("r").takeIf { it > 0 }?.let { ellipse(n("cx"), n("cy"), it, it) }
            "ellipse" -> if (n("rx") > 0 && n("ry") > 0) ellipse(n("cx"), n("cy"), n("rx"), n("ry")) else null
            "rect" -> {
                val w = n("width"); val h = n("height")
                if (w <= 0 || h <= 0) null else {
                    var rx = number(e.getAttribute("rx")); var ry = number(e.getAttribute("ry"))
                    if (rx == null) rx = ry
                    if (ry == null) ry = rx
                    roundRect(n("x"), n("y"), w, h, (rx ?: 0f).coerceIn(0f, w / 2), (ry ?: 0f).coerceIn(0f, h / 2))
                }
            }
            "line" -> listOf(PathOp.MoveTo(n("x1"), n("y1")), PathOp.LineTo(n("x2"), n("y2")))
            "polyline", "polygon" -> {
                val p = SvgPath.numbers(e.getAttribute("points"))
                if (p.size < 4) null else buildList {
                    add(PathOp.MoveTo(p[0], p[1]))
                    var i = 2
                    while (i + 1 < p.size) { add(PathOp.LineTo(p[i], p[i + 1])); i += 2 }
                    if (tag == "polygon") add(PathOp.Close)
                }
            }
            else -> null
        }
    }

    private const val K = 0.5522848f

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) = listOf(
        PathOp.MoveTo(cx + rx, cy),
        PathOp.CubicTo(cx + rx, cy + ry * K, cx + rx * K, cy + ry, cx, cy + ry),
        PathOp.CubicTo(cx - rx * K, cy + ry, cx - rx, cy + ry * K, cx - rx, cy),
        PathOp.CubicTo(cx - rx, cy - ry * K, cx - rx * K, cy - ry, cx, cy - ry),
        PathOp.CubicTo(cx + rx * K, cy - ry, cx + rx, cy - ry * K, cx + rx, cy),
        PathOp.Close,
    )

    fun roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float): List<PathOp> {
        if (rx <= 0 || ry <= 0) return listOf(PathOp.MoveTo(x, y), PathOp.LineTo(x + w, y), PathOp.LineTo(x + w, y + h), PathOp.LineTo(x, y + h), PathOp.Close)
        val kx = rx * K; val ky = ry * K
        return listOf(
            PathOp.MoveTo(x + rx, y), PathOp.LineTo(x + w - rx, y),
            PathOp.CubicTo(x + w - rx + kx, y, x + w, y + ry - ky, x + w, y + ry),
            PathOp.LineTo(x + w, y + h - ry),
            PathOp.CubicTo(x + w, y + h - ry + ky, x + w - rx + kx, y + h, x + w - rx, y + h),
            PathOp.LineTo(x + rx, y + h),
            PathOp.CubicTo(x + rx - kx, y + h, x, y + h - ry + ky, x, y + h - ry),
            PathOp.LineTo(x, y + ry),
            PathOp.CubicTo(x, y + ry - ky, x + rx - kx, y, x + rx, y),
            PathOp.Close,
        )
    }
}

/** SVG path data → ops, including elliptical arcs (as cubic Béziers). */
object SvgPath {
    fun numbers(s: String): List<Float> {
        val sc = Scanner(s)
        val out = mutableListOf<Float>()
        while (true) out += sc.number() ?: break
        return out
    }

    fun parse(d: String): List<PathOp>? {
        val s = Scanner(d)
        val ops = mutableListOf<PathOp>()
        var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
        var lastCtrl: Pair<Float, Float>? = null
        var lastCmd = ' '
        var cmd = ' '
        var hasPoint = false

        fun ensureStart() { if (!hasPoint) { ops += PathOp.MoveTo(cx, cy); hasPoint = true; sx = cx; sy = cy } }

        while (true) {
            s.skip()
            val c = s.peek() ?: break
            if (Scanner.isCommand(c)) { cmd = c; s.advance() }
            else if (cmd == ' ' || cmd == 'Z' || cmd == 'z') break
            val rel = cmd.isLowerCase()
            val up = cmd.uppercaseChar()
            fun px(x: Float) = if (rel) cx + x else x
            fun py(y: Float) = if (rel) cy + y else y
            when (up) {
                'M' -> {
                    val x = s.number() ?: return done(ops); val y = s.number() ?: return done(ops)
                    cx = px(x); cy = py(y); ops += PathOp.MoveTo(cx, cy); sx = cx; sy = cy; hasPoint = true; lastCtrl = null
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> {
                    val x = s.number() ?: return done(ops); val y = s.number() ?: return done(ops)
                    ensureStart(); cx = px(x); cy = py(y); ops += PathOp.LineTo(cx, cy); lastCtrl = null
                }
                'H' -> { val x = s.number() ?: return done(ops); ensureStart(); cx = if (rel) cx + x else x; ops += PathOp.LineTo(cx, cy); lastCtrl = null }
                'V' -> { val y = s.number() ?: return done(ops); ensureStart(); cy = if (rel) cy + y else y; ops += PathOp.LineTo(cx, cy); lastCtrl = null }
                'C' -> {
                    val v = List(6) { s.number() ?: return done(ops) }
                    ensureStart()
                    val x1 = px(v[0]); val y1 = py(v[1]); val x2 = px(v[2]); val y2 = py(v[3]); val x = px(v[4]); val y = py(v[5])
                    ops += PathOp.CubicTo(x1, y1, x2, y2, x, y); lastCtrl = x2 to y2; cx = x; cy = y
                }
                'S' -> {
                    val v = List(4) { s.number() ?: return done(ops) }
                    ensureStart()
                    val reflect = (lastCmd == 'C' || lastCmd == 'S') && lastCtrl != null
                    val x1 = if (reflect) 2 * cx - lastCtrl!!.first else cx
                    val y1 = if (reflect) 2 * cy - lastCtrl!!.second else cy
                    val x2 = px(v[0]); val y2 = py(v[1]); val x = px(v[2]); val y = py(v[3])
                    ops += PathOp.CubicTo(x1, y1, x2, y2, x, y); lastCtrl = x2 to y2; cx = x; cy = y
                }
                'Q' -> {
                    val v = List(4) { s.number() ?: return done(ops) }
                    ensureStart()
                    val x1 = px(v[0]); val y1 = py(v[1]); val x = px(v[2]); val y = py(v[3])
                    ops += PathOp.QuadTo(x1, y1, x, y); lastCtrl = x1 to y1; cx = x; cy = y
                }
                'T' -> {
                    val x0 = s.number() ?: return done(ops); val y0 = s.number() ?: return done(ops)
                    ensureStart()
                    val reflect = (lastCmd == 'Q' || lastCmd == 'T') && lastCtrl != null
                    val x1 = if (reflect) 2 * cx - lastCtrl!!.first else cx
                    val y1 = if (reflect) 2 * cy - lastCtrl!!.second else cy
                    val x = px(x0); val y = py(y0)
                    ops += PathOp.QuadTo(x1, y1, x, y); lastCtrl = x1 to y1; cx = x; cy = y
                }
                'A' -> {
                    val rx = s.number() ?: return done(ops); val ry = s.number() ?: return done(ops)
                    val rot = s.number() ?: return done(ops); val large = s.flag() ?: return done(ops)
                    val sweep = s.flag() ?: return done(ops)
                    val x0 = s.number() ?: return done(ops); val y0 = s.number() ?: return done(ops)
                    ensureStart()
                    val x = px(x0); val y = py(y0)
                    arc(ops, cx, cy, rx, ry, rot, large, sweep, x, y)
                    cx = x; cy = y; lastCtrl = null
                }
                'Z' -> { if (hasPoint) ops += PathOp.Close; cx = sx; cy = sy; lastCtrl = null }
                else -> return done(ops)
            }
            lastCmd = up
        }
        return done(ops)
    }

    private fun done(ops: List<PathOp>) = ops.takeIf { it.isNotEmpty() }

    fun arc(ops: MutableList<PathOp>, x0: Float, y0: Float, rxIn: Float, ryIn: Float, angle: Float,
            large: Boolean, sweep: Boolean, x: Float, y: Float) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { ops += PathOp.LineTo(x, y); return }
        val phi = Math.toRadians(angle.toDouble())
        val cosP = cos(phi); val sinP = sin(phi)
        val dx2 = (x0 - x) / 2.0; val dy2 = (y0 - y) / 2.0
        val x1p = cosP * dx2 + sinP * dy2
        val y1p = -sinP * dx2 + cosP * dy2
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = coef * -ry * x1p / rx
        val ccx = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
        val ccy = sinP * cxp + cosP * cyp + (y0 + y) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
        val theta1 = ang(1.0, 0.0, ux, uy)
        var delta = ang(ux, uy, vx, vy)
        if (!sweep && delta > 0) delta -= 2 * Math.PI else if (sweep && delta < 0) delta += 2 * Math.PI
        val segments = maxOf(1, ceil(abs(delta) / (Math.PI / 2) - 1e-7).toInt())
        val step = delta / segments
        val t = 4.0 / 3.0 * tan(step / 4)
        fun map(u: Double, v: Double) = (ccx + rx * cosP * u - ry * sinP * v).toFloat() to (ccy + rx * sinP * u + ry * cosP * v).toFloat()
        var a1 = theta1
        for (i in 0 until segments) {
            val a2 = a1 + step
            val c1 = map(cos(a1) - t * sin(a1), sin(a1) + t * cos(a1))
            val c2 = map(cos(a2) + t * sin(a2), sin(a2) - t * cos(a2))
            val end = if (i == segments - 1) x to y else map(cos(a2), sin(a2))
            ops += PathOp.CubicTo(c1.first, c1.second, c2.first, c2.second, end.first, end.second)
            a1 = a2
        }
    }

    private class Scanner(text: String) {
        private val s = text
        private var i = 0

        companion object { fun isCommand(c: Char) = c in "MmLlHhVvCcSsQqTtAaZz" }

        fun peek(): Char? = s.getOrNull(i)
        fun advance() { i++ }
        fun skip() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }

        fun flag(): Boolean? {
            skip()
            val c = peek() ?: return null
            if (c != '0' && c != '1') return null
            i++
            return c == '1'
        }

        fun number(): Float? {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var digits = 0
            while (i < s.length && s[i].isDigit()) { i++; digits++ }
            if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) { i++; digits++ } }
            if (digits == 0) { i = start; return null }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                var exp = 0
                while (i < s.length && s[i].isDigit()) { i++; exp++ }
                if (exp == 0) i = save
            }
            return s.substring(start, i).toFloatOrNull()
        }
    }
}
