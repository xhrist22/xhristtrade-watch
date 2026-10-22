package com.xhristtrade.watch

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun WatchApp() {
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf(0L) }
    var everLoaded by remember { mutableStateOf(false) }

    // poll fast, forever; ride through a brief upstream blip (502/restart) showing the LAST data, retry quickly
    LaunchedEffect(Unit) {
        while (true) {
            var ok = true
            when (val r = Net.fetchWatch()) {
                is FetchResult.Ok -> {
                    if (r.feed.rows.isNotEmpty()) rows = r.feed.rows
                    error = null; updatedAt = System.currentTimeMillis(); everLoaded = true
                }
                is FetchResult.Err -> { ok = false; error = r.message; if (!everLoaded) rows = emptyList() }
            }
            // 6s when healthy (server cache is ~instant); 2.5s retry after a blip so it snaps back fast
            delay(if (ok) 6_000 else 2_500)
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            if (rows.isEmpty()) {
                SplashOrError(error)
            } else {
                // ♾ looping deck — swiping past the last page wraps smartly back to the first (both directions).
                // A big virtual page count started in the middle gives seamless wrap with no snap.
                val real = rows.size + 1                 // assets + the hub page
                val pager = rememberPagerState(initialPage = real * 1000, pageCount = { real * 2000 })
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    val idx = ((page % real) + real) % real
                    if (idx < rows.size) AssetCard(rows[idx], idx, rows.size)
                    else HubPage(rows.size, updatedAt, error)
                }
            }
        }
    }
}

@Composable
private fun SplashOrError(error: String?) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("XhristTrade", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            error ?: "Loading your desk…",
            color = if (error != null) C.down else C.muted,
            fontSize = 12.sp, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AssetCard(r: Row, idx: Int, total: Int) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(22.dp)) // clear the TimeText

        // ── header: asset · side · price ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.asset, color = C.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(sideArrow(r.side), color = sideColor(r.side), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(fmtPrice(r.entry), color = C.text, fontFamily = MonoNum, fontSize = 15.sp)
        // dots = which page
        Text(dots(idx, total), color = C.muted, fontSize = 9.sp)

        // call / verdict — keeps the "N short" count; a ⬜ marks short-by-just-1 (one check from gold)
        r.call?.let {
            Spacer(Modifier.height(2.dp))
            Text(prettyCall(it), color = callColor(r.signal), fontSize = 11.sp, textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold)
        }
        // ✈️ how far it ALREADY WENT, in ATR (straight from the feed's own `far`, e.g. "↑0.33× ATR")
        r.far?.let {
            Text("✈️ went $it", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }

        // legs meter
        if (r.legs != null && r.lit != null) {
            Spacer(Modifier.height(3.dp))
            Text("${r.lit}/${r.legs} legs lit", color = C.muted, fontSize = 10.sp)
        }

        Spacer(Modifier.height(8.dp))

        // ── 30-min sparkline ──
        SectionLabel("30 MIN")
        Sparkline(r.spark, Modifier.fillMaxWidth().height(34.dp))

        Spacer(Modifier.height(8.dp))

        // ── KURU ──
        r.kuru?.let { k ->
            SectionLabel("KURU · ${k.lean ?: "—"} · ${if (k.rising) "firing" else "coiling"}${if (k.agree) " · all 3" else ""}")
            KuruChart(k, Modifier.fillMaxWidth().height(40.dp))
            Spacer(Modifier.height(8.dp))
        }

        // ── WAE ──
        r.wae?.let { w ->
            SectionLabel("WAE · ${w.side ?: "—"}${if (w.firing) " · firing" else ""}${w.mode?.let { " · $it" } ?: ""}")
            WaeChart(w, Modifier.fillMaxWidth().height(40.dp))
            Spacer(Modifier.height(8.dp))
        }

        // ── LRC channel — bar + text aligned to the rails (top = upper rail, bottom = lower rail) ──
        r.ind?.let { ind ->
            SectionLabel("LRC")
            Row(verticalAlignment = Alignment.CenterVertically) {
                LrcBar(ind, Modifier.width(22.dp).height(96.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.height(96.dp).weight(1f), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("▔ ${fmtPrice(ind.upper)}", color = C.muted, fontFamily = MonoNum, fontSize = 9.sp)
                    Column {
                        val pct = ind.bc?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() }
                        Text(fmtPrice(ind.px ?: r.entry), color = indColor(ind), fontFamily = MonoNum,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (pct != null) "$pct% up channel" else "in channel", color = C.muted, fontSize = 9.sp)
                        r.lrcLeg?.let { Text(it, color = indColor(ind), fontSize = 9.sp) }
                    }
                    Text("▁ ${fmtPrice(ind.lower)}", color = C.muted, fontFamily = MonoNum, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── plan: every row aligned on = like the ChartWire desk ──
        SectionLabel("PLAN")
        PlanRow("side", "${sideArrow(r.side)} ${r.side ?: "—"}", sideColor(r.side))
        r.entry?.let { PlanRow("now", fmtPrice(it), C.text) }
        r.stop?.let { s ->
            val tag = if (s.green) (if (s.pays) "🟩 pays" else "🟩") else "🟧"
            val atr = s.atrAway?.let { " ·${it.fmt1()}×ATR" } ?: ""
            PlanRow("SL", "$tag ${fmtPrice(s.price)}$atr", if (s.green) C.up else C.gold)
            s.rr?.let { PlanRow("RR", "%.2f".format(it), rrColor(it)) }
        }
        r.target?.let { t ->
            val reach = t.reachPct?.let { " ·${it}%" } ?: ""
            val src = t.from?.let { " ·$it" } ?: ""
            PlanRow("TP3", "📈 ${fmtPrice(t.price)}$reach$src", C.text)
            if (r.stop?.rr == null) t.rr?.let { PlanRow("RR", "%.2f".format(it), rrColor(it)) }
        }
        r.backup?.let { b -> PlanRow("2nd SL", "🟧 ${fmtPrice(b.price)}", C.gold) }

        // ── why it isn't a trade yet ──
        r.reason?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionLabel(s: String) {
    Text(s, color = C.muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    Spacer(Modifier.height(2.dp))
}

// Each row is  <label> = <value>  with the label in a fixed-width, right-aligned, monospace column,
// so every "=" lines up vertically down the plan — the ChartWire desk look.
@Composable
private fun PlanRow(label: String, value: String, valueColor: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = C.muted, fontSize = 11.sp, fontFamily = MonoNum,
            textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(46.dp))
        Text(" = ", color = C.muted, fontSize = 11.sp, fontFamily = MonoNum)
        Text(value, color = valueColor, fontSize = 11.sp, fontFamily = MonoNum, maxLines = 1)
    }
}

@Composable
private fun HubPage(count: Int, updatedAt: Long, error: String?) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(22.dp))
        Text("XhristTrade", color = C.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("$count assets · swipe ‹ ›", color = C.muted, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Text(freshness(updatedAt, error), color = if (error != null) C.down else C.muted,
            fontSize = 10.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Chip(
            onClick = { openOnPhone(ctx) },
            label = { Text("Open QT on phone", fontSize = 13.sp) },
            colors = ChipDefaults.primaryChipColors(backgroundColor = C.accent),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
    }
}

private fun openOnPhone(ctx: android.content.Context) {
    try {
        val helper = RemoteActivityHelper(ctx)
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse(BuildConfig.PHONE_URL))
        val future = helper.startRemoteActivity(intent)
        future.addListener({
            val ok = try { future.get(); true } catch (e: Exception) { false }
            Toast.makeText(ctx, if (ok) "Opening on your phone" else "Couldn't reach the phone", Toast.LENGTH_SHORT).show()
        }, ContextCompat.getMainExecutor(ctx))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Couldn't open on the phone", Toast.LENGTH_SHORT).show()
    }
}

// ── small helpers ──
// Keep the "N short" count; when it's short by just 1, prepend ⬜ (one check short of 🟨 gold — Sam's own convention).
private fun prettyCall(call: String): String {
    val m = Regex("""\b1 short\b""").containsMatchIn(call)
    return if (m) call.replace(Regex("""\b1 short\b"""), "⬜ 1 short") else call
}
private fun sideArrow(side: String?): String = when (side?.uppercase()) {
    "BUY" -> "▲"; "SELL" -> "▼"; else -> "⇅"
}
private fun callColor(signal: String?): Color = when (signal?.lowercase()) {
    "green" -> C.up; "red" -> C.down; "yellow", "gold" -> C.gold; else -> C.muted
}
private fun rrColor(rr: Double): Color = when {
    rr >= 2.0 -> C.up; rr >= 1.3 -> C.gold; else -> C.down
}
private fun dots(idx: Int, total: Int): String {
    val sb = StringBuilder()
    for (i in 0 until total) sb.append(if (i == idx) "●" else "·")
    return sb.toString()
}
private fun Double.fmt1(): String = "%.1f".format(this)

private fun freshness(updatedAt: Long, error: String?): String {
    if (error != null) return error
    if (updatedAt == 0L) return "Loading…"
    val s = ((System.currentTimeMillis() - updatedAt) / 1000).toInt()
    return if (s <= 1) "updated just now" else "updated ${s}s ago"
}
