package com.xhristtrade.watch

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private fun clamp01(v: Double): Float = v.coerceIn(0.0, 1.0).toFloat()

fun indColor(ind: Ind?): Color {
    val c = ind?.col
    if (c != null && c.size == 3) return Color(c[0].coerceIn(0, 255), c[1].coerceIn(0, 255), c[2].coerceIn(0, 255))
    return hueColor(ind?.hue)
}

// ── 30-min sparkline ──
@Composable
fun Sparkline(spark: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        if (spark.size < 2) return@Canvas
        val lo = spark.min(); val hi = spark.max()
        val span = (hi - lo).let { if (it == 0.0) 1.0 else it }
        val padY = size.height * 0.14f
        val h = size.height - padY * 2
        val n = spark.size
        fun x(i: Int) = size.width * i / (n - 1)
        fun y(v: Double) = padY + (h - ((v - lo) / span * h)).toFloat()
        val rising = spark.last() >= spark.first()
        val col = if (rising) C.up else C.down

        // soft area
        val area = Path().apply {
            moveTo(x(0), size.height)
            lineTo(x(0), y(spark[0]))
            for (i in 1 until n) lineTo(x(i), y(spark[i]))
            lineTo(x(n - 1), size.height)
            close()
        }
        drawPath(area, color = col.copy(alpha = 0.12f))

        val line = Path().apply {
            moveTo(x(0), y(spark[0]))
            for (i in 1 until n) lineTo(x(i), y(spark[i]))
        }
        drawPath(line, color = col, style = Stroke(width = 2.2f.dp()))
        // end dot
        drawCircle(col, radius = 2.6f.dp(), center = Offset(x(n - 1), y(spark.last())))
    }
}

// ── KURU: Lead / 2x / 4x histogram around a zero line ──
@Composable
fun KuruChart(k: Kuru, modifier: Modifier) {
    Canvas(modifier) {
        val s = k.series
        if (s.isEmpty()) return@Canvas
        val n = s.size
        val mid = size.height / 2f
        val half = size.height / 2f * 0.92f
        val scale = if (k.scale <= 0) 1.0 else k.scale
        val slot = size.width / n
        // zero line
        drawLine(C.grid, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)

        fun bar(i: Int, v: Double, wFrac: Float, alpha: Float) {
            if (v == 0.0) return
            val hgt = ((v / scale) * half).toFloat().coerceIn(-half, half)
            val w = slot * wFrac
            val cx = slot * i + slot / 2f
            val col = (if (v >= 0) C.up else C.down).copy(alpha = alpha)
            val top = if (hgt >= 0) mid - hgt else mid
            val bh = abs(hgt)
            drawRect(col, topLeft = Offset(cx - w / 2f, top), size = androidx.compose.ui.geometry.Size(w, bh))
        }
        // back → front: 4x faint widest, 2x medium, Lead bright thin
        for (i in 0 until n) bar(i, s[i].getOrElse(2) { 0.0 }, 0.92f, 0.22f) // k4
        for (i in 0 until n) bar(i, s[i].getOrElse(1) { 0.0 }, 0.58f, 0.45f) // k2
        for (i in 0 until n) bar(i, s[i].getOrElse(0) { 0.0 }, 0.30f, 0.95f) // k1 Lead
    }
}

// ── WAE: up/dn columns + explosion + dead-zone lines ──
@Composable
fun WaeChart(w: Wae, modifier: Modifier) {
    Canvas(modifier) {
        val s = w.series
        if (s.isEmpty()) return@Canvas
        val n = s.size
        val scale = if (w.scale <= 0) 1.0 else w.scale
        val h = size.height * 0.96f
        val base = size.height
        val slot = size.width / n
        fun yv(v: Double) = base - ((v / scale) * h).toFloat().coerceIn(0f, h)

        // columns
        for (i in 0 until n) {
            val up = s[i].getOrElse(0) { 0.0 }
            val dn = s[i].getOrElse(1) { 0.0 }
            val mag = max(up, dn)
            if (mag <= 0) continue
            val col = if (up >= dn) C.up else C.down
            val bw = slot * 0.62f
            val cx = slot * i + slot / 2f
            val top = yv(mag)
            drawRect(col.copy(alpha = 0.85f), topLeft = Offset(cx - bw / 2f, top), size = androidx.compose.ui.geometry.Size(bw, base - top))
        }
        // explosion line (yellow)
        val expl = Path(); val dead = Path()
        var started = false; var startedD = false
        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            val e1 = s[i].getOrElse(2) { 0.0 }
            val dz = s[i].getOrElse(3) { 0.0 }
            val ey = yv(e1); val dy = yv(dz)
            if (!started) { expl.moveTo(cx, ey); started = true } else expl.lineTo(cx, ey)
            if (!startedD) { dead.moveTo(cx, dy); startedD = true } else dead.lineTo(cx, dy)
        }
        drawPath(dead, color = C.deadzone.copy(alpha = 0.8f),
            style = Stroke(width = 1.4f.dp(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))))
        drawPath(expl, color = C.explosion, style = Stroke(width = 1.8f.dp()))
    }
}

// ── LRC channel — a glass tube in the LRC colours (aqua top → red bottom), like QT's heat meter.
//    The bright marker is PRICE's place in the channel; the orange tick is EMA9; the dashed line is fair value (mid). ──
@Composable
fun LrcBar(ind: Ind, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tubeW = w * 0.64f
        val x0 = (w - tubeW) / 2f
        val f: Float = when {
            ind.bc != null -> clamp01(ind.bc)
            ind.px != null && ind.upper != null && ind.lower != null && ind.upper != ind.lower ->
                clamp01((ind.px - ind.lower) / (ind.upper - ind.lower))
            else -> 0.5f
        }
        val eF: Float? = if (ind.ema9 != null && ind.upper != null && ind.lower != null && ind.upper != ind.lower)
            clamp01((ind.ema9 - ind.lower) / (ind.upper - ind.lower)) else null
        fun y(frac: Float) = h - frac * h
        val rad = tubeW / 2f
        val cr = androidx.compose.ui.geometry.CornerRadius(rad)

        // the channel itself: ONE gradient in the LRC zone colours (top strong → bottom weak)
        val brush = Brush.verticalGradient(
            0f to C.lrcAqua, 0.25f to C.lrcGreen, 0.5f to C.lrcYellow, 0.75f to C.lrcOrange, 1f to C.lrcRed,
        )
        drawRoundRect(brush = brush, topLeft = Offset(x0, 0f),
            size = androidx.compose.ui.geometry.Size(tubeW, h), cornerRadius = cr, alpha = 0.52f)
        // glass sheen down the left of the tube
        drawRoundRect(C.text.copy(alpha = 0.08f), topLeft = Offset(x0 + tubeW * 0.12f, h * 0.04f),
            size = androidx.compose.ui.geometry.Size(tubeW * 0.24f, h * 0.92f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(tubeW * 0.12f))
        // tube outline
        drawRoundRect(C.border, topLeft = Offset(x0, 0f),
            size = androidx.compose.ui.geometry.Size(tubeW, h), cornerRadius = cr, style = Stroke(width = 1.2f))
        // fair-value midline (dashed)
        drawLine(C.bg.copy(alpha = 0.5f), Offset(x0, y(0.5f)), Offset(x0 + tubeW, y(0.5f)),
            strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        // EMA9 tick (orange, like the chart's EMA9)
        eF?.let {
            drawLine(C.explosion, Offset(x0 - 5f, y(it)), Offset(x0 + tubeW + 5f, y(it)), strokeWidth = 2.5f, cap = StrokeCap.Round)
        }
        // PRICE marker — a bright level line + a puck in the current zone colour, legible on any band
        val cy = y(f); val cx = w / 2f
        drawLine(C.text, Offset(x0 - 4f, cy), Offset(x0 + tubeW + 4f, cy), strokeWidth = 2f, cap = StrokeCap.Round)
        drawCircle(C.bg, radius = tubeW * 0.44f, center = Offset(cx, cy))
        drawCircle(indColor(ind), radius = tubeW * 0.36f, center = Offset(cx, cy))
    }
}

// dp helper inside DrawScope (px already — scale small stroke widths by density)
private fun Float.dp(): Float = this // strokes are in px; kept small and dpi-reasonable
private fun DrawScope.dpUnused() {}
