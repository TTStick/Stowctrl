package com.wanwu.nook.ui.art

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.wanwu.nook.data.Catalog
import com.wanwu.nook.data.Furniture
import com.wanwu.nook.data.FurnitureLayer
import com.wanwu.nook.data.FurnitureStyle
import com.wanwu.nook.data.MaterialKind
import com.wanwu.nook.ui.theme.hexColor

/**
 * Top-down furniture drawings, ported from ios/Nook/Views/Home/FurnitureArt.swift.
 * Art is drawn unrotated in a w × d box with the front edge at the bottom.
 */
object FurnitureArt {
    data class Palette(val fill: Color, val stroke: Color) {
        val detail get() = stroke.copy(alpha = stroke.alpha * 0.55f)
        val soft get() = stroke.copy(alpha = stroke.alpha * 0.18f)
    }

    fun fill(m: MaterialKind, dark: Boolean) = Color(if (dark) m.darkFill else m.lightFill)
    fun stroke(m: MaterialKind, dark: Boolean) = Color(if (dark) m.darkStroke else m.lightStroke)

    fun palette(f: Furniture, dark: Boolean): Palette {
        val kind = Catalog.kind(f.kind)
        f.colorHex?.let { hex ->
            val c = hexColor(hex)
            val darker = Color(c.red * 0.65f, c.green * 0.65f, c.blue * 0.65f)
            return Palette(c, darker)
        }
        return Palette(fill(kind.material, dark), stroke(kind.material, dark))
    }

    /** Draws `f` filling `size` (its on-screen footprint), handling rotation. */
    fun DrawScope.drawFurniture(f: Furniture, size: Size, dark: Boolean) {
        val kind = Catalog.kind(f.kind)
        val quarter = f.rot % 2 == 1
        val art = if (quarter) Size(size.height, size.width) else size
        withTransform({
            translate(size.width / 2, size.height / 2)
            rotate(f.rot * 90f, pivot = Offset.Zero)
            translate(-art.width / 2, -art.height / 2)
        }) {
            val style = if (f.kind == Catalog.CUSTOM) FurnitureStyle.cabinet else kind.style
            drawArt(style, Rect(Offset.Zero, art), palette(f, dark), kind.layer == FurnitureLayer.wall, dark)
        }
    }

    private fun DrawScope.rr(r: Rect, radius: Float, color: Color, stroke: Stroke? = null) {
        val rad = radius.coerceIn(0f, minOf(r.width, r.height) / 2)
        if (stroke == null) drawRoundRect(color, r.topLeft, r.size, CornerRadius(rad))
        else drawRoundRect(color, r.topLeft, r.size, CornerRadius(rad), style = stroke)
    }

    private fun DrawScope.oval(r: Rect, color: Color, stroke: Stroke? = null) {
        if (stroke == null) drawOval(color, r.topLeft, r.size) else drawOval(color, r.topLeft, r.size, style = stroke)
    }

    fun DrawScope.drawArt(style: FurnitureStyle, r: Rect, p: Palette, overhead: Boolean, dark: Boolean) {
        val m = minOf(r.width, r.height)
        val lw = (m * 0.035f).coerceIn(0.6f, 1.6f) * density
        val corner = (m * 0.08f).coerceIn(1f, 6f * density)
        val body = Rect(r.left + lw / 2, r.top + lw / 2, r.right - lw / 2, r.bottom - lw / 2)

        fun base(fill: Color = p.fill) {
            rr(body, corner, fill)
            rr(body, corner, p.stroke, Stroke(lw, pathEffect = if (overhead) PathEffect.dashPathEffect(floatArrayOf(3 * density, 2 * density)) else null))
        }
        fun line(a: Offset, b: Offset, color: Color = p.detail, width: Float = lw, dash: Boolean = false) =
            drawLine(color, a, b, width, StrokeCap.Round, if (dash) PathEffect.dashPathEffect(floatArrayOf(2 * density, 2 * density)) else null)
        fun dot(c: Offset, radius: Float, color: Color = p.detail) = drawCircle(color, radius, c)
        fun frontStrip(fraction: Float = 0.14f): Rect {
            val h = maxOf(lw * 2, r.height * fraction)
            return Rect(r.left + lw, r.bottom - h - lw / 2, r.right - lw, r.bottom - lw / 2)
        }
        fun adaptive(light: Long, darkC: Long) = Color(if (dark) darkC else light)

        when (style) {
            FurnitureStyle.cabinet -> {
                base()
                val s = frontStrip()
                drawRect(p.soft, s.topLeft, s.size)
                val doors = if (r.width > r.height * 2.4f) 3 else if (r.width > r.height * 1.1f) 2 else 1
                for (i in 1 until doors) {
                    val x = r.left + r.width * i / doors
                    line(Offset(x, s.top), Offset(x, s.bottom))
                }
                for (i in 0 until doors) dot(Offset(r.left + r.width * (i + 0.5f) / doors, s.center.y), maxOf(0.8f, lw))
            }
            FurnitureStyle.wardrobe -> {
                base()
                val s = frontStrip(0.12f)
                drawRect(p.soft, s.topLeft, s.size)
                val rodY = r.top + (s.top - r.top) * 0.5f
                line(Offset(r.left + r.width * 0.06f, rodY), Offset(r.right - r.width * 0.06f, rodY), p.stroke)
                val hangers = maxOf(2, (r.width / maxOf(m * 0.5f, 4f)).toInt())
                for (i in 0 until hangers) {
                    val x = r.left + r.width * (i + 0.5f) / hangers
                    line(Offset(x, rodY - m * 0.18f), Offset(x, rodY + m * 0.18f), p.detail, lw * 0.8f)
                }
                val doors = if (r.width > r.height * 2) 3 else 2
                for (i in 1 until doors) {
                    val x = r.left + r.width * i / doors
                    line(Offset(x, s.top), Offset(x, s.bottom))
                }
            }
            FurnitureStyle.drawers -> {
                base()
                val s = frontStrip(0.16f)
                drawRect(p.soft, s.topLeft, s.size)
                line(Offset(s.center.x - s.width * 0.18f, s.center.y), Offset(s.center.x + s.width * 0.18f, s.center.y), p.stroke, lw * 1.4f)
            }
            FurnitureStyle.shelf -> {
                base(p.fill.copy(alpha = 0.75f))
                val rows = if (r.height > r.width * 0.8f) 3 else 2
                for (i in 1..rows) {
                    val y = r.top + r.height * i / (rows + 1)
                    line(Offset(r.left + lw * 2, y), Offset(r.right - lw * 2, y), p.detail, lw, dash = true)
                }
            }
            FurnitureStyle.bed -> {
                base()
                val inset = m * 0.05f
                val mattress = Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
                rr(mattress, corner, adaptive(0xFFFBFAF7, 0xFF4A4C50))
                val pillowH = mattress.height * 0.16f
                val pillows = if (r.width / r.height > 0.75f) 2 else 1
                val gap = mattress.width * 0.05f
                val pw = (mattress.width - gap * (pillows + 1)) / pillows
                for (i in 0 until pillows) {
                    val pr = Rect(Offset(mattress.left + gap + i * (pw + gap), mattress.top + gap * 0.8f), Size(pw, pillowH))
                    rr(pr, pillowH * 0.4f, adaptive(0xFFFFFFFF, 0xFF5C5F64))
                    rr(pr, pillowH * 0.4f, p.detail, Stroke(lw * 0.8f))
                }
                val blanket = Rect(Offset(mattress.left, mattress.top + mattress.height * 0.32f), Size(mattress.width, mattress.height * 0.68f))
                rr(blanket, corner, fill(MaterialKind.fabric, dark))
                val fy = blanket.top + blanket.height * 0.12f
                line(Offset(blanket.left, fy), Offset(blanket.right, fy), stroke(MaterialKind.fabric, dark), lw)
            }
            FurnitureStyle.sofa, FurnitureStyle.armchair -> {
                base()
                val back = r.height * 0.26f
                val arm = minOf(r.width * 0.13f, r.height * 0.24f)
                val shade = p.stroke.copy(alpha = 0.35f)
                rr(Rect(r.left, r.top, r.right, r.top + back), corner, shade)
                rr(Rect(r.left, r.top, r.left + arm, r.bottom), corner, shade)
                rr(Rect(r.right - arm, r.top, r.right, r.bottom), corner, shade)
                val seat = Rect(r.left + arm, r.top + back, r.right - arm, r.bottom - lw)
                val cushions = if (style == FurnitureStyle.armchair) 1 else if (seat.width / seat.height > 2.2f) 3 else 2
                val cw = seat.width / cushions
                for (i in 0 until cushions) {
                    val cr = Rect(seat.left + i * cw + lw, seat.top + lw, seat.left + (i + 1) * cw - lw, seat.bottom - lw)
                    rr(cr, corner, p.detail, Stroke(lw * 0.8f))
                }
            }
            FurnitureStyle.table -> {
                base()
                val ins = m * 0.1f
                rr(Rect(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins), corner, p.soft, Stroke(lw))
            }
            FurnitureStyle.dining -> {
                val chairD = r.height * 0.18f
                val table = Rect(Offset(r.left + r.width * 0.06f, r.top + chairD + lw), Size(r.width * 0.88f, r.height - chairD * 2 - lw * 2))
                val n = (table.width / maxOf(chairD * 1.6f, 1f)).toInt().coerceIn(1, 4)
                val cw = minOf(table.width / n * 0.62f, chairD * 1.3f)
                for (i in 0 until n) {
                    val cx = table.left + table.width * (i + 0.5f) / n
                    for (top in listOf(true, false)) {
                        val cr = Rect(Offset(cx - cw / 2, if (top) r.top + lw else table.bottom + lw * 0.5f), Size(cw, chairD - lw))
                        rr(cr, corner, p.fill.copy(alpha = 0.8f))
                        rr(cr, corner, p.stroke, Stroke(lw * 0.8f))
                    }
                }
                rr(table, corner, p.fill)
                rr(table, corner, p.stroke, Stroke(lw))
            }
            FurnitureStyle.desk -> {
                base()
                rr(Rect(Offset(r.center.x - r.width * 0.2f, r.top + r.height * 0.12f), Size(r.width * 0.4f, maxOf(lw * 2, r.height * 0.08f))), 1f, fill(MaterialKind.dark, dark))
                val ins = m * 0.1f
                rr(Rect(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins), corner, p.soft, Stroke(lw))
            }
            FurnitureStyle.chair -> {
                val seat = Rect(r.left + r.width * 0.08f, r.top + r.height * 0.08f, r.right - r.width * 0.08f, r.bottom - r.height * 0.08f)
                rr(seat, corner * 1.5f, p.fill)
                rr(seat, corner * 1.5f, p.stroke, Stroke(lw))
                rr(Rect(seat.left, seat.top, seat.right, seat.top + seat.height * 0.24f), corner, p.stroke.copy(alpha = 0.4f))
            }
            FurnitureStyle.tv -> {
                base()
                line(Offset(r.left + r.width * 0.05f, r.bottom - lw * 1.5f), Offset(r.right - r.width * 0.05f, r.bottom - lw * 1.5f), adaptive(0xFF7FB5D9, 0xFF5D8FB0), lw)
            }
            FurnitureStyle.fridge -> {
                base()
                val s = frontStrip(0.12f)
                drawRect(p.soft, s.topLeft, s.size)
                line(Offset(r.center.x, s.top), Offset(r.center.x, s.bottom))
                val hy = s.top - lw * 1.5f
                line(Offset(r.center.x - r.width * 0.12f, hy), Offset(r.center.x - r.width * 0.04f, hy), p.stroke, lw * 1.3f)
                line(Offset(r.center.x + r.width * 0.04f, hy), Offset(r.center.x + r.width * 0.12f, hy), p.stroke, lw * 1.3f)
            }
            FurnitureStyle.stove -> {
                base()
                val rad = minOf(r.width / 4, r.height / 4) * 0.62f
                for ((fx, fy) in listOf(0.28f to 0.3f, 0.72f to 0.3f, 0.28f to 0.7f, 0.72f to 0.7f)) {
                    val c = Offset(r.left + r.width * fx, r.top + r.height * fy)
                    drawCircle(fill(MaterialKind.dark, dark), rad, c, style = Stroke(lw * 1.2f))
                    dot(c, rad * 0.35f, fill(MaterialKind.dark, dark))
                }
            }
            FurnitureStyle.sink -> {
                base()
                val basin = Rect(Offset(r.left + r.width * 0.18f, r.top + r.height * 0.22f), Size(r.width * 0.64f, r.height * 0.56f))
                rr(basin, corner * 2, adaptive(0xFFE3EEF4, 0xFF3F4D56))
                rr(basin, corner * 2, p.stroke, Stroke(lw))
                dot(basin.center, maxOf(0.8f, m * 0.03f), p.stroke)
                dot(Offset(r.center.x, r.top + r.height * 0.12f), maxOf(1f, m * 0.05f), p.stroke)
            }
            FurnitureStyle.toilet -> {
                val tank = Rect(Offset(r.left + r.width * 0.05f, r.top), Size(r.width * 0.9f, r.height * 0.28f))
                rr(tank, corner, p.fill); rr(tank, corner, p.stroke, Stroke(lw))
                val bowl = Rect(Offset(r.left + r.width * 0.1f, r.top + r.height * 0.24f), Size(r.width * 0.8f, r.height * 0.74f))
                oval(bowl, p.fill); oval(bowl, p.stroke, Stroke(lw))
                oval(Rect(bowl.left + bowl.width * 0.18f, bowl.top + bowl.height * 0.16f, bowl.right - bowl.width * 0.18f, bowl.bottom - bowl.height * 0.16f), p.detail, Stroke(lw * 0.8f))
            }
            FurnitureStyle.bathtub -> {
                rr(r, corner * 2, p.fill); rr(r, corner * 2, p.stroke, Stroke(lw))
                val inner = Rect(r.left + m * 0.12f, r.top + m * 0.12f, r.right - m * 0.12f, r.bottom - m * 0.12f)
                rr(inner, m * 0.3f, adaptive(0xFFDCEBF4, 0xFF3E4F5A))
                rr(inner, m * 0.3f, p.detail, Stroke(lw))
                dot(Offset(inner.left + inner.width * 0.12f, inner.center.y), maxOf(0.8f, m * 0.04f), p.stroke)
            }
            FurnitureStyle.shower -> {
                base()
                line(Offset(r.left + lw, r.top + lw), Offset(r.right - lw, r.bottom - lw), p.soft)
                line(Offset(r.right - lw, r.top + lw), Offset(r.left + lw, r.bottom - lw), p.soft)
                drawCircle(p.stroke, m * 0.08f, r.center, style = Stroke(lw))
            }
            FurnitureStyle.washer -> {
                base()
                val rad = m * 0.32f
                val c = Offset(r.center.x, r.center.y + m * 0.06f)
                drawCircle(adaptive(0xFFDDE7EE, 0xFF46525A), rad, c)
                drawCircle(p.stroke, rad, c, style = Stroke(lw * 1.2f))
                drawCircle(p.detail, rad * 0.6f, c, style = Stroke(lw * 0.8f))
                line(Offset(r.left + r.width * 0.15f, r.top + r.height * 0.11f), Offset(r.left + r.width * 0.45f, r.top + r.height * 0.11f))
            }
            FurnitureStyle.appliance -> {
                base()
                val s = frontStrip(0.16f)
                drawRect(p.soft, s.topLeft, s.size)
                rr(Rect(Offset(r.left + r.width * 0.12f, r.top + r.height * 0.18f), Size(r.width * 0.76f, r.height * 0.5f)), corner, p.detail, Stroke(lw))
                dot(Offset(s.right - s.width * 0.12f, s.center.y), maxOf(0.8f, lw))
            }
            FurnitureStyle.door -> {
                drawRect(Color.Gray.copy(alpha = 0.35f), r.topLeft, Size(r.width, maxOf(lw * 2, r.height * 0.06f)))
                val radius = minOf(r.width, r.height) * 0.98f
                drawArc(p.detail, 0f, 90f, false, Offset(r.left - radius, r.top - radius), Size(radius * 2, radius * 2),
                    style = Stroke(lw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * density, 2 * density))))
                line(Offset(r.left + lw, r.top), Offset(r.left + lw, r.top + radius), p.stroke, lw * 2)
            }
            FurnitureStyle.window -> {
                drawRect(p.fill, r.topLeft, r.size)
                drawRect(p.stroke, r.topLeft, r.size, style = Stroke(lw))
                line(Offset(r.left, r.center.y), Offset(r.right, r.center.y), p.stroke, lw * 0.8f)
            }
            FurnitureStyle.wall, FurnitureStyle.pillar -> drawRect(adaptive(0xFF4A4A4A, 0xFF9A9A9A), r.topLeft, r.size)
            FurnitureStyle.plant -> {
                val pot = Rect(r.left + r.width * 0.22f, r.top + r.height * 0.22f, r.right - r.width * 0.22f, r.bottom - r.height * 0.22f)
                oval(pot, fill(MaterialKind.cardboard, dark))
                for ((fx, fy, sc) in listOf(Triple(0.35f, 0.35f, 0.34f), Triple(0.65f, 0.38f, 0.32f), Triple(0.5f, 0.62f, 0.36f), Triple(0.3f, 0.62f, 0.26f), Triple(0.7f, 0.66f, 0.26f))) {
                    val rad = m * sc * 0.55f
                    val c = Offset(r.left + r.width * fx, r.top + r.height * fy)
                    drawCircle(p.fill.copy(alpha = 0.9f), rad, c)
                    drawCircle(p.stroke, rad, c, style = Stroke(lw * 0.7f))
                }
            }
            FurnitureStyle.rug -> {
                rr(r, corner, p.fill.copy(alpha = 0.7f))
                val ins = m * 0.08f
                rr(Rect(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins), corner, p.stroke.copy(alpha = 0.6f),
                    Stroke(lw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * density, 3 * density))))
                val tassels = maxOf(4, (r.height / (6 * density)).toInt())
                for (i in 0 until tassels) {
                    val y = r.top + r.height * (i + 0.5f) / tassels
                    line(Offset(r.left - m * 0.02f, y), Offset(r.left + m * 0.03f, y), p.detail, lw * 0.6f)
                    line(Offset(r.right - m * 0.03f, y), Offset(r.right + m * 0.02f, y), p.detail, lw * 0.6f)
                }
            }
            FurnitureStyle.lamp -> {
                val shade = Rect(r.left + r.width * 0.12f, r.top + r.height * 0.12f, r.right - r.width * 0.12f, r.bottom - r.height * 0.12f)
                oval(shade, adaptive(0xFFFFF4CF, 0xFF6B6040))
                oval(shade, p.stroke, Stroke(lw))
                dot(r.center, m * 0.08f, p.stroke)
            }
            FurnitureStyle.box -> {
                base()
                line(Offset(r.center.x, r.top + lw), Offset(r.center.x, r.bottom - lw), p.detail, lw * 1.4f)
                line(Offset(r.left + lw, r.center.y), Offset(r.right - lw, r.center.y), p.soft, lw)
            }
            FurnitureStyle.basket -> {
                base()
                val step = maxOf(3f * density, m * 0.18f)
                var x = r.left + step
                while (x < r.right) { line(Offset(x, r.top + lw * 2), Offset(x, r.bottom - lw * 2), p.soft, lw * 0.8f); x += step }
            }
            FurnitureStyle.ac -> {
                base()
                for (f in listOf(0.35f, 0.55f, 0.75f)) {
                    val y = r.top + r.height * f
                    line(Offset(r.left + r.width * 0.08f, y), Offset(r.right - r.width * 0.08f, y), p.detail, lw * 0.8f)
                }
            }
            FurnitureStyle.generic -> base()
        }
    }

    /** Miniature of all furniture in a room (floor plan tiles). */
    fun DrawScope.drawRoomFurniture(pieces: List<Furniture>, scale: Float, dark: Boolean) {
        for (f in pieces) {
            translate(f.x.toFloat() * scale, f.y.toFloat() * scale) {
                drawFurniture(f, Size(f.footprintWidth.toFloat() * scale, f.footprintDepth.toFloat() * scale), dark)
            }
        }
    }
}
