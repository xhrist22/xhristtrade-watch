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

    // poll every 12s, forever (foreground watch app)
    LaunchedEffect(Unit) {
        while (true) {
            when (val r = Net.fetchWatch()) {
                is FetchResult.Ok -> {
                    if (r.feed.rows.isNotEmpty()) rows = r.feed.rows
                    error = null; updatedAt = System.currentTimeMillis(); everLoaded = true
                }
                is FetchResult.Err -> { error = r.message; if (!everLoaded) rows = emptyList() }
            }
            delay(12_000)
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            if (rows.isEmpty()) {
                SplashOrError(error)
            } else {
                val pageCount = rows.size + 1
                val pager = rememberPagerState(pageCount = { pageCount })
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    if (page < rows.size) AssetCard(rows[page], page, rows.size)
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

        // call / verdict
        r.call?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = callColor(r.signal), fontSize = 11.sp, textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold)
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

        // ── LRC channel + its leg ──
        r.ind?.let { ind ->
            SectionLabel("LRC")
            Row(verticalAlignment = Alignment.CenterVertically) {
                LrcBar(ind, Modifier.width(26.dp).height(84.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    ind.upper?.let { Text("▔ ${fmtPrice(it)}", color = C.muted, fontFamily = MonoNum, fontSize = 9.sp) }
                    r.lrcLeg?.let { Text(it, color = indColor(ind), fontSize = 10.sp) }
                    ind.lower?.let { Text("▁ ${fmtPrice(it)}", color = C.muted, fontFamily = MonoNum, fontSize = 9.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── plan: SL / TP3 / RR ──
        SectionLabel("PLAN")
        r.stop?.let { s ->
            val tag = if (s.green) (if (s.pays) "🟩 pays" else "🟩") else "🟧"
            val atr = s.atrAway?.let { " · ${(it).fmt1()}×ATR" } ?: ""
            PlanRow("$tag SL", "${fmtPrice(s.price)}$atr", if (s.green) C.up else C.gold)
            s.rr?.let { PlanRow("⚖️ RR", "%.2f".format(it), rrColor(it)) }
        }
        r.target?.let { t ->
            val reach = t.reachPct?.let { " · ${it}%" } ?: ""
            val src = t.from?.let { " · $it" } ?: ""
            PlanRow("📈 TP3", "${fmtPrice(t.price)}$reach$src", C.text)
            if (r.stop?.rr == null) t.rr?.let { PlanRow("⚖️ RR", "%.2f".format(it), rrColor(it)) }
        }
        r.backup?.let { b ->
            PlanRow("🟧 2nd SL", fmtPrice(b.price), C.gold)
        }

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

@Composable
private fun PlanRow(label: String, value: String, valueColor: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = C.text, fontSize = 11.sp)
        Text(value, color = valueColor, fontFamily = MonoNum, fontSize = 11.sp, textAlign = TextAlign.End)
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
