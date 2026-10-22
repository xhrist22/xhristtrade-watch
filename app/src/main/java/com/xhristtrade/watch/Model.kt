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
    val pos: Double?,       // signed half-widths from the middle (±1 = the rails, >1 broke up, <-1 broke down)
    val col: IntArray?,     // [r,g,b] parsed from "rgb(r,g,b)"
    val hue: String?,       // "aqua" / "yellow" / "red" — the candle temperature word
)

// the 1h LRC candle colour (compact — just what the 1m/1h lines need)
data class Lrc1h(val hue: String?, val pos: Double?, val col: IntArray?)

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
    val reachPct: Int?,            // 📊 chance price REACHES this stop from here (history magnet) = the SL hit %
)

data class Target(
    val price: Double?,
    val rr: Double?,
    val from: String?,             // "IQ Hot" / "HVN" / "TP3" …
    val magnet: Boolean,
    val reachPct: Int?,
)

// 🎞 the 1h tape read — who hit harder over the last CLOSED hour (buy% vs sell%) + a plain-English "means", in sync with the QT candle card
data class Tape(val buy: Int?, val sell: Int?, val means: String?)

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
    val far: String?,              // "↑0.33× ATR" — how far it already went (the ✈️ stretched distance)
    val signal: String?,           // "green" / "red" / …
    val signalWhy: String?,
    val lrcLeg: String?,
    val ind: Ind?,
    val ind1h: Lrc1h?,
    val entry: Double?,
    val stop: Stop?,
    val backup: Stop?,
    val target: Target?,
    val atr: Double?,
    val kuru: Kuru?,
    val wae: Wae?,
    val spark: List<Double>,       // 30 one-minute closes
    val brokers: List<Broker>,     // every account incl. the 2 FTMO, each with its gap on this asset (watch-only field)
    val tape: Tape?,               // 🎞 the 1h tape (who hit harder) + plain means
)

// a broker/account you can price the board in — name, its gap vs the chart on THIS asset (null = never measured), its mode
data class Broker(val name: String, val basis: Double?, val mode: String?)

data class Feed(val rows: List<Row>)

// ── parsing helpers (org.json; JSON null → Kotlin null, NaN → null) ──

internal fun JSONObject.dbl(k: String): Double? {
    if (!has(k) || isNull(k)) return null
    val v = optDouble(k, Double.NaN)
    return if (v.isNaN()) null else v
}
internal fun JSONObject.boolf(k: String): Boolean = optBoolean(k, false)
internal fun JSONObject.intn(k: String): Int? {
    if (!has(k) || isNull(k)) return null
    return optInt(k, Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) null else it }
}
internal fun JSONObject.strn(k: String): String? {
    if (!has(k) || isNull(k)) return null
    val s = optString(k, "")
    return if (s.isEmpty()) null else s
}
internal fun JSONObject.objn(k: String): JSONObject? = if (!has(k) || isNull(k)) null else optJSONObject(k)
internal fun JSONObject.arrn(k: String): JSONArray? = if (!has(k) || isNull(k)) null else optJSONArray(k)

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
            ema9 = indO.dbl("ema9"), bc = indO.dbl("bc"), pos = indO.dbl("pos"),
            col = parseRgb(indO.strn("col")), hue = indO.strn("hue"),
        )
        val i1hO = o.objn("ind_1h")
        val ind1h = if (i1hO == null) null else Lrc1h(
            hue = i1hO.strn("hue"), pos = i1hO.dbl("pos"), col = parseRgb(i1hO.strn("col")),
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
            pays = so.boolf("pays"), kind = so.strn("kind"), green = so.boolf("green"), reachPct = so.intn("reach_pct"),
        )

        val tO = o.objn("target")
        val target = if (tO == null) null else Target(
            price = tO.dbl("price"), rr = tO.dbl("rr"), from = tO.strn("from"),
            magnet = tO.boolf("magnet"), reachPct = tO.intn("reach_pct"),
        )
        val tpO = o.objn("tape")
        val tape = if (tpO == null) null else Tape(buy = tpO.intn("buy"), sell = tpO.intn("sell"), means = tpO.strn("means"))

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
                far = o.strn("far"),
                signal = o.strn("signal"),
                signalWhy = o.strn("signal_why"),
                lrcLeg = o.strn("lrc_leg"),
                ind = ind,
                ind1h = ind1h,
                entry = o.dbl("entry"),
                stop = stopOf(o.objn("stop")),
                backup = stopOf(o.objn("backup")),
                target = target,
                atr = o.dbl("atr"),
                kuru = kuru,
                wae = wae,
                spark = doubleList(o.arrn("spark")),
                brokers = run {
                    val arr = o.arrn("brokers"); val out = ArrayList<Broker>()
                    if (arr != null) for (k in 0 until arr.length()) {
                        val bo = arr.optJSONObject(k) ?: continue
                        val nm = bo.strn("name") ?: continue
                        out.add(Broker(nm, bo.dbl("basis"), bo.strn("mode")))
                    }
                    out
                },
                tape = tape,
            )
        )
    }
    return Feed(rows)
}

// pretty price: match the terminal's asset-aware decimals
fun fmtPrice(v: Double?): String {
    if (v == null) return "—"
    val a = kotlin.math.abs(v)
    // 🔢 _QT_PRICE_2DP_2026_10_22 — 2 decimals for every normal-priced value (≥ $1), matching the QT app + watchlist; sub-$1 keeps 5dp.
    val dp = if (a >= 1.0) 2 else 5
    return "%,.${dp}f".format(v)
}
