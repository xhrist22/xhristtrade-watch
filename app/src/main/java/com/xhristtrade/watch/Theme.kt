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
    val magenta = Color(0xFFC084FC)   // 🥊 _QT_WAE_AGAINST_DISTINCT_2026_10_23 — WAE "against", distinct from the red "active sell"
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
    "yellow" -> "middle"   // _QT_ZONE_PLAIN_WORDS_2026_10_22 — plain words ("mid"/"bot" read like jargon; "bot" looked like a trading bot)
    "orange" -> "lower"
    "red" -> if ((pos ?: 0.0) < -1) "broke↓" else "bottom"
    else -> "—"
}

// 🛰️ _QT_WATCH_LRC_SITUATION_2026_10_22 — the LRC section's one-line situation: how far price ALREADY WENT in ATR when it is stretched
// OUT past the rails (the feed's own ✈️ `far`, e.g. "↑0.33× ATR"); otherwise ✓ it is still INSIDE the channel + WHERE (the same zone
// word the ● 1m/1h lines use). So the watch always says either "it went this far out" or "it's in, here's the status".
fun lrcSituation(far: String?, hue: String?, pos: Double?): String {
    // ✈️ _QT_FAR_ONLY_WHEN_OUT_2026_10_22 — only "went" when price is ACTUALLY past a rail (|pos| > 1); never say went while price is inside.
    val out = pos != null && (pos > 1.0 || pos < -1.0)
    return when {
        out && !far.isNullOrBlank() -> "✈️ went $far"
        out -> "✈️ went ${if ((pos ?: 0.0) > 0) "↑" else "↓"} out"
        else -> "✓ in channel · ${lrcZone(hue, pos)}"
    }
}

// the WAE desk mode variant with its own emoji (active / lite / pause / against / fading)
fun waeMode(mode: String?, dying: Boolean?): String = when (mode?.lowercase()) {
    "active", "firing", "fire" -> "✔ active"   // 🟥🟩 _QT_WAE_ACTIVE_CHECK_BY_SIDE_2026_10_23 — a monochrome check so the caller can tint it by the WAE side
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
