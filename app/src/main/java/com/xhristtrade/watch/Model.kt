package com.xhristtrade.watch

import org.json.JSONArray
import org.json.JSONObject

// ── the row shapes mirror /api/qe/watch exactly (validated against the live feed 2026-10-22) ──

data class Ind(
    val px: Double?,
    val upper: Double?,
    val lower: Double?,
    val ema9: Double?,
    val bc: Double?,        // price's fraction in the channel: 0 = lower rail, 1 = upper rail
    val col: IntArray?,     // [r,g,b] parsed from "rgb(r,g,b)"
    val hue: String?,       // "aqua" / "yellow" / "red" — the candle temperature word
)

data class Kuru(
    val series: List<DoubleArray>, // each: [k1 (Lead), k2 (2x), k4 (4x)]
    val scale: Double,
    val lean: String?,             // "bull" / "bear" / "flat"
    val agree: Boolean,            // all three on the same side
    val rising: Boolean,           // |Lead| expanding vs the last 5 bars (coiling vs firing)
    val strength: Double,
)

data class Wae(
    val series: List<DoubleArray>, // each: [up, dn, e1 (explosion), dz (dead zone)]
    val scale: Double,
    val side: String?,             // "buy" / "sell"
    val firing: Boolean,
    val mode: String?,             // "active" / "lite" / "pause" / "against" / "fading" (desk word)
    val dying: Boolean?,
)

data class Stop(
    val price: Double?,
    val rr: Double?,
    val atrAway: Double?,          // only present on the blessed 🟩 stop
    val pays: Boolean,             // the desk blessed it (🟩)
    val kind: String?,
    val green: Boolean,
)

data class Target(
    val price: Double?,
    val rr: Double?,
    val from: String?,             // "IQ Hot" / "HVN" / "TP3" …
    val magnet: Boolean,
    val reachPct: Int?,
)

data class Row(
    val asset: String,
    val symbol: String?,
    val side: String?,             // "BUY" / "SELL"
    val closed: Boolean,
    val verdict: String?,
    val call: String?,             // e.g. "⚪ WAIT | 2 short"
    val aligned: Boolean,
    val lit: Int?,
    val legs: Int?,
    val reason: String?,
    val signal: String?,           // "green" / "red" / …
    val signalWhy: String?,
    val lrcLeg: String?,
    val ind: Ind?,
    val entry: Double?,
    val stop: Stop?,
    val backup: Stop?,
    val target: Target?,
    val atr: Double?,
    val kuru: Kuru?,
    val wae: Wae?,
    val spark: List<Double>,       // 30 one-minute closes
)

data class Feed(val rows: List<Row>)

// ── parsing helpers (org.json; JSON null → Kotlin null, NaN → null) ──

private fun JSONObject.dbl(k: String): Double? {
    if (!has(k) || isNull(k)) return null
    val v = optDouble(k, Double.NaN)
    return if (v.isNaN()) null else v
}
private fun JSONObject.boolf(k: String): Boolean = optBoolean(k, false)
private fun JSONObject.intn(k: String): Int? {
    if (!has(k) || isNull(k)) return null
    return optInt(k, Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
}
private fun JSONObject.strn(k: String): String? {
    if (!has(k) || isNull(k)) return null
    val s = optString(k, "")
    return if (s.isEmpty()) null else s
}
private fun JSONObject.objn(k: String): JSONObject? = if (!has(k) || isNull(k)) null else optJSONObject(k)
private fun JSONObject.arrn(k: String): JSONArray? = if (!has(k) || isNull(k)) null else optJSONArray(k)

private fun parseRgb(s: String?): IntArray? {
    if (s == null) return null
    val m = Regex("""(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""").find(s) ?: return null
    return IntArray(3) { m.groupValues[it + 1].toIntOrNull() ?: 0 }
}

private fun doubleList(a: JSONArray?): List<Double> {
    if (a == null) return emptyList()
    val out = ArrayList<Double>(a.length())
    for (i in 0 until a.length()) {
        val v = a.optDouble(i, Double.NaN)
        if (!v.isNaN()) out.add(v)
    }
    return out
}

private fun seriesOf(a: JSONArray?, width: Int): List<DoubleArray> {
    if (a == null) return emptyList()
    val out = ArrayList<DoubleArray>(a.length())
    for (i in 0 until a.length()) {
        val inner = a.optJSONArray(i) ?: continue
        val row = DoubleArray(width)
        for (j in 0 until width) row[j] = inner.optDouble(j, 0.0).let { if (it.isNaN()) 0.0 else it }
        out.add(row)
    }
    return out
}

fun parseFeed(body: String): Feed {
    val root = JSONObject(body)
    val arr = root.optJSONArray("rows") ?: JSONArray()
    val rows = ArrayList<Row>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue

        val indO = o.objn("ind")
        val ind = if (indO == null) null else Ind(
            px = indO.dbl("px"), upper = indO.dbl("upper"), lower = indO.dbl("lower"),
            ema9 = indO.dbl("ema9"), bc = indO.dbl("bc"),
            col = parseRgb(indO.strn("col")), hue = indO.strn("hue"),
        )

        val kO = o.objn("kuru")
        val kuru = if (kO == null) null else Kuru(
            series = seriesOf(kO.arrn("series"), 3),
            scale = kO.dbl("scale") ?: 1.0,
            lean = kO.strn("lean"), agree = kO.boolf("agree"),
            rising = kO.boolf("rising"), strength = kO.dbl("strength") ?: 0.0,
        )

        val wO = o.objn("wae")
        val wae = if (wO == null) null else Wae(
            series = seriesOf(wO.arrn("series"), 4),
            scale = wO.dbl("scale") ?: 1.0,
            side = wO.strn("side"), firing = wO.boolf("firing"),
            mode = wO.strn("mode"),
            dying = if (wO.has("dying") && !wO.isNull("dying")) wO.boolf("dying") else null,
        )

        fun stopOf(so: JSONObject?): Stop? = if (so == null) null else Stop(
            price = so.dbl("price"), rr = so.dbl("rr"), atrAway = so.dbl("atr_away"),
            pays = so.boolf("pays"), kind = so.strn("kind"), green = so.boolf("green"),
        )

        val tO = o.objn("target")
        val target = if (tO == null) null else Target(
            price = tO.dbl("price"), rr = tO.dbl("rr"), from = tO.strn("from"),
            magnet = tO.boolf("magnet"), reachPct = tO.intn("reach_pct"),
        )

        rows.add(
            Row(
                asset = o.optString("asset", "?"),
                symbol = o.strn("symbol"),
                side = o.strn("side"),
                closed = o.boolf("closed"),
                verdict = o.strn("verdict"),
                call = o.strn("call"),
                aligned = o.boolf("aligned"),
                lit = o.intn("lit"),
                legs = o.intn("legs"),
                reason = o.strn("reason"),
                signal = o.strn("signal"),
                signalWhy = o.strn("signal_why"),
                lrcLeg = o.strn("lrc_leg"),
                ind = ind,
                entry = o.dbl("entry"),
                stop = stopOf(o.objn("stop")),
                backup = stopOf(o.objn("backup")),
                target = target,
                atr = o.dbl("atr"),
                kuru = kuru,
                wae = wae,
                spark = doubleList(o.arrn("spark")),
            )
        )
    }
    return Feed(rows)
}

// pretty price: match the terminal's asset-aware decimals
fun fmtPrice(v: Double?): String {
    if (v == null) return "—"
    val a = kotlin.math.abs(v)
    val dp = when {
        a >= 1000 -> 1
        a >= 10 -> 2
        a >= 1 -> 3
        else -> 5
    }
    return "%,.${dp}f".format(v)
}
