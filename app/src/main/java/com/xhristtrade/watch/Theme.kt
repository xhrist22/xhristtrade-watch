package com.xhristtrade.watch

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Exact Quant Terminal / ChartWire dark tokens (public/css/app.css :root[data-theme="dark"]).
object C {
    val bg = Color(0xFF131722)
    val panel = Color(0xFF1E222D)
    val panel2 = Color(0xFF2A2E39)
    val border = Color(0xFF2A2E39)
    val text = Color(0xFFD1D4DC)
    val muted = Color(0xFF787B86)
    val accent = Color(0xFF2962FF)
    val up = Color(0xFF089981)      // green
    val down = Color(0xFFF23645)    // red
    val gold = Color(0xFFD4A514)
    val grid = Color(0x992A2E39)
    val yellow = Color(0xFFD4A514)
    val aqua = Color(0xFF26C6DA)
    val explosion = Color(0xFFE5B80B) // WAE explosion line (yellow/gold)
    val deadzone = Color(0xFF42A5F5)  // WAE dead-zone line (blue)
    // LRC channel zones — the EXACT palette QT's heat meter + desk use (aqua top → red bottom)
    val lrcAqua = Color(0xFF00BCD4)
    val lrcGreen = Color(0xFF7FD487)
    val lrcYellow = Color(0xFFFFEB3B)
    val lrcOrange = Color(0xFFF8A52B)
    val lrcRed = Color(0xFFF01313)
}

// The desk's own words for where the candle sits in the channel (ranked.js SAY) — plain, no "% up channel".
fun lrcWord(hue: String?, pos: Double?): String = when (hue?.lowercase()) {
    "blue", "cyan", "aqua" -> if ((pos ?: 0.0) > 1) "🟦 broke up" else "🟦 top of channel"
    "green" -> "🟩 upper half"
    "yellow" -> "🟨 middle · coiling"
    "orange" -> "🟧 lower half"
    "red" -> if ((pos ?: 0.0) < -1) "🟥 broke down" else "🟥 bottom of channel"
    else -> "— reading…"
}

// the LRC zone colour → a Compose Color (the heat palette)
fun hueColor(hue: String?): Color = when (hue?.lowercase()) {
    "blue", "cyan", "aqua" -> C.lrcAqua
    "green" -> C.lrcGreen
    "yellow" -> C.lrcYellow
    "orange" -> C.lrcOrange
    "red" -> C.lrcRed
    else -> C.muted
}

// the EXACT LRC candle colour (continuous rgb from lrc.js) — the full aqua→green→yellow→orange→red range,
// not just 5 buckets; falls back to the zone palette only when no rgb was sent.
fun rgbColor(col: IntArray?, hue: String?): Color =
    if (col != null && col.size == 3) Color(col[0].coerceIn(0, 255), col[1].coerceIn(0, 255), col[2].coerceIn(0, 255))
    else hueColor(hue)

// short zone word (the colour swatch beside it carries the real candle colour)
fun lrcZone(hue: String?, pos: Double?): String = when (hue?.lowercase()) {
    "blue", "cyan", "aqua" -> if ((pos ?: 0.0) > 1) "broke↑" else "top"
    "green" -> "upper"
    "yellow" -> "mid"
    "orange" -> "lower"
    "red" -> if ((pos ?: 0.0) < -1) "broke↓" else "bot"
    else -> "—"
}

// the WAE desk mode variant with its own emoji (active / lite / pause / against / fading)
fun waeMode(mode: String?, dying: Boolean?): String = when (mode?.lowercase()) {
    "active", "firing", "fire" -> "✅ active"
    "lite" -> "🪶 lite"
    "pause", "paused", "off" -> "⏸ pause"
    "against" -> "🥊 against"
    "fading", "dying", "spent" -> "🟠 fading"
    else -> if (dying == true) "🟠 fading" else (mode ?: "—")
}

// is EMA9 inside the LRC channel or pushed out past a rail?
fun ema9Where(upper: Double?, lower: Double?, ema9: Double?): String = when {
    ema9 == null || upper == null || lower == null -> "—"
    ema9 > upper -> "above rail ▲ (out)"
    ema9 < lower -> "below rail ▼ (out)"
    else -> "in channel"
}

// what that colour most likely means for the next move
fun lrcMeaning(hue: String?): String = when (hue?.lowercase()) {
    "blue", "cyan", "aqua" -> "stretched↑, may fade"
    "green" -> "strength, leaning ↑"
    "yellow" -> "coiling, no edge — wait"
    "orange" -> "weak, leaning ↓"
    "red" -> "stretched↓, may bounce"
    else -> "reading…"
}

// Numbers are monospace to match the terminal (ui-monospace in app.css).
val MonoNum = FontFamily.Monospace

// A side's colour (BUY green, SELL red) the terminal way.
fun sideColor(side: String?): Color = when (side?.uppercase()) {
    "BUY" -> C.up
    "SELL" -> C.down
    else -> C.muted
}
