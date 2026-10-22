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
}

// Numbers are monospace to match the terminal (ui-monospace in app.css).
val MonoNum = FontFamily.Monospace

// A side's colour (BUY green, SELL red) the terminal way.
fun sideColor(side: String?): Color = when (side?.uppercase()) {
    "BUY" -> C.up
    "SELL" -> C.down
    else -> C.muted
}
