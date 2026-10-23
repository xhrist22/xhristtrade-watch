package com.xhristtrade.watch

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 🧭 _QT_WATCH_BEZEL_SCROLL_2026_10_23 — Sam: "move up and down on watchlist and asset pages … by moving my galaxy bezel up and down". The
// rotating bezel / crown drives the visible page's vertical scroll (onRotaryScrollEvent → scrollBy; e.verticalScrollPixels is already
// direction-correct + system-tuned). Only the pager's CURRENT page holds focus (rememberActiveFocusRequester inside its per-page
// HierarchicalFocusCoordinator), so the bezel always scrolls the page you're looking at and never a neighbour kept warm offscreen.
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
internal fun Modifier.bezelScroll(state: ScrollState): Modifier {
    val fr = rememberActiveFocusRequester()
    val scope = rememberCoroutineScope()
    return this
        .onRotaryScrollEvent { e -> scope.launch { state.scrollBy(e.verticalScrollPixels) }; true }
        .focusRequester(fr)
        .focusable()
}

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun WatchApp() {
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf(0L) }
    var everLoaded by remember { mutableStateOf(false) }

    // 🔋 _QT_WATCH_BATTERY_LIFECYCLE_2026_10_23 — poll fast while the watch face is ON, but STOP entirely when the screen turns off /
    // the app backgrounds (wrist-down, ambient). A plain LaunchedEffect(Unit) coroutine keeps running while the Activity is merely
    // STOPPED, so the old loop hit the network every 6 s ALL DAY = the dominant battery drain. repeatOnLifecycle(RESUMED) pauses the
    // loop at screen-off and re-enters it — with an immediate fresh fetch — the instant you raise your wrist. No speed lost on-screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
    }

    var broker by remember { mutableStateOf("Chart") }   // default: the engine / chart price

    // ⌚🔐 _QT_WATCH_ORDERS_SIGNED_2026_10_22 — the trade side: a signed read every 4 s (trades + the exact account roster); not yet
    // approved ⇒ the Trades page shows the pairing code. `poke` re-reads at once after a press.
    var wo by remember { mutableStateOf(WoUi(null, null, null)) }
    var poke by remember { mutableStateOf(0) }
    LaunchedEffect(poke) {
        // 🔋 same lifecycle gate as the feed loop: the signed trades read pauses when the screen is off, resumes on wrist-raise.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                wo = when (val r = Orders.signed("trades")) {
                    is WoResult.Ok -> WoUi(true, parseTrades(r.json), null)
                    is WoResult.Unpaired -> WoUi(false, null, null)
                    is WoResult.Err -> wo.copy(error = r.why)
                }
                delay(if (wo.paired == true) 4_000 else 6_000)
            }
        }
    }
    val onChanged: () -> Unit = { poke++ }

    Scaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            if (rows.isEmpty()) {
                SplashOrError(error)
            } else {
                // page 0 = BROKER PICKER · 1 = TRADES · 2 = 🔥 MARKET HEAT · 3 = WATCHLIST · 4..N+3 = assets re-priced to the pick · last = hub. Loops both ways.
                val brokers = rows.firstOrNull()?.brokers ?: emptyList()
                val real = rows.size + 5   // 📋 watchlist + 🔥 _QT_WATCH_MARKET_HEAT_PAGE_2026_10_23 (broker · trades · heat · watchlist · hub = 5 non-asset pages)
                val pager = rememberPagerState(initialPage = real * 1000, pageCount = { real * 2000 })
                // 👆 _QT_WATCH_WL_TAP_TO_ASSET_2026_10_23 — tap a watchlist/heat row → jump to THAT asset's page (row i lives at page idx i+4 now,
                // after the 🔥 market-heat page was inserted). Scroll within the CURRENT loop block so the move is one short swipe, not across the loop.
                val navScope = rememberCoroutineScope()
                val goToAsset: (Int) -> Unit = { i ->
                    navScope.launch {
                        val cur = pager.currentPage
                        val curIdx = ((cur % real) + real) % real
                        pager.animateScrollToPage(cur - curIdx + (i + 4))
                    }
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                  HierarchicalFocusCoordinator(requiresFocus = { pager.currentPage == page }) {   // 🧭 only the visible page gets the bezel
                    val idx = ((page % real) + real) % real
                    when {
                        idx == 0 -> BrokerPicker(brokers, broker) { broker = it }
                        idx == 1 -> TradesPage(wo, onChanged)
                        idx == 2 -> MarketHeatPage(rows, goToAsset)   // 🔥 _QT_WATCH_MARKET_HEAT_PAGE_2026_10_23 — every asset's heat, tap to expand its 1m/1h breakdown
                        idx == 3 -> WatchlistPage(rows, broker, goToAsset)   // 📋 the whole board at a glance, same format as QT; tap a row → its asset page
                        idx <= rows.size + 3 -> {
                            val r = rows[idx - 4]
                            val basis = if (broker == "Chart") 0.0 else (r.brokers.firstOrNull { it.name == broker }?.basis ?: 0.0)
                            AssetCard(r, idx - 4, rows.size, broker, basis, wo, onChanged)
                        }
                        else -> HubPage(rows.size, updatedAt, error)
                    }
                  }
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
private fun AssetCard(r: Row, idx: Int, total: Int, brokerName: String, basis: Double, wo: WoUi, onChanged: () -> Unit) {
    fun sp(v: Double?): Double? = v?.let { it + basis }   // shift a chart price into the selected broker's price
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).bezelScroll(scroll)   // 🧭 bezel scrolls this page
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp)) // clear the TimeText

        // 🚩 _QT_WATCH_TOP_BANNERS_2026_10_22 — on TOP, following the QT data: 🌙 market closed (last snapshot) · ⏸ bot stood down · 📅 event
        TopBanners(r)

        // 🏦 which broker these prices are in — shown on EVERY page
        Text("🏦 $brokerName" + if (basis != 0.0) "  (${if (basis > 0) "+" else ""}${basis.fmt2()})" else "",
            color = C.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)

        // ── header: asset · side · price ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.asset, color = C.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.width(6.dp))
            Text(sideArrow(r.side), color = sideColor(r.side), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(fmtPrice(sp(r.entry)), color = C.text, fontFamily = MonoNum, fontSize = 15.sp)
        // dots = which page
        Text(dots(idx, total), color = C.muted, fontSize = 9.sp)

        // call / verdict — keeps the "N short" count; a ⬜ marks short-by-just-1 (one check from gold)
        r.call?.let {
            Spacer(Modifier.height(2.dp))
            Text(prettyCall(it), color = callColor(r.signal), fontSize = 11.sp, textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold)
        }

        // legs meter — the SAME blue bar as the watchlist (ticks + lit/legs count), not the plain "5/7 legs" text
        if (r.legs != null && r.lit != null) {
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { LegsInline(r.lit, r.legs) }
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
            SectionLabel("WAE · ${w.side ?: "—"}${if (w.firing) " · firing" else ""}")
            WaeChart(w, Modifier.fillMaxWidth().height(40.dp))
            // 🤝 _QT_KURU_WAE_VERDICT_2026_10_23 — directly below the WAE graph: the SAME KURU + WAE "→ price …" verdict the QT chart banner shows
            r.kw?.let { kv ->
                Spacer(Modifier.height(5.dp))
                KwVerdictCard(kv)
            }
            // 🌙 _QT_WATCH_CLOSED_SNAPSHOT_2026_10_22 — when the market is shut, the mode has no live reading (was a bare "mode = —"); say it's
            // the frozen last snapshot instead of a dash. The 🌙 CLOSED banner on top already flags the whole page as a snapshot.
            // 🟥🟩 _QT_WAE_ACTIVE_CHECK_BY_SIDE_2026_10_23 — the ✔ active mode is tinted by the WAE side (green buy / red sell), same as the row chip
            PlanRow("mode", if (r.closed) "🌙 last snapshot" else waeMode(w.mode, w.dying),
                if (r.closed) C.muted
                else if (w.mode?.lowercase() in listOf("active", "firing", "fire")) (if (w.side?.lowercase() == "sell") C.down else C.up)
                else C.text)
            // 🗓 _QT_WATCH_GAMMA_DAY_2026_10_23 — below mode, the option-dealer day type (same tag as the QT watchlist): 🏃 trend · ↔️ range · ⚖️ mixed
            r.gammaDay?.let { d -> dayText(d)?.let { PlanRow("day", it, dayColor(d)) } }
            Spacer(Modifier.height(8.dp))
        }

        // ── LRC channel — bar + text aligned to the rails (top = upper rail, bottom = lower rail) ──
        r.ind?.let { ind ->
            SectionLabel("LRC")
            // ✈️ how far it ALREADY WENT (ATR, from the feed's `far`) when price is stretched OUT past a rail; otherwise ✓ the in-channel status
            Text(lrcSituation(r.far, ind.hue, ind.pos),
                color = if (!r.far.isNullOrBlank()) C.explosion else C.up,
                fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LrcBar(ind, Modifier.width(22.dp).height(96.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.height(96.dp).weight(1f), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("▔ ${fmtPrice(sp(ind.upper))}", color = C.muted, fontFamily = MonoNum, fontSize = 9.sp)
                    Column {
                        // the LRC (green) price reads the SAME "now" as the header, and follows the broker (sp)
                        Text(fmtPrice(sp(r.entry ?: ind.px)), color = indColor(ind), fontFamily = MonoNum,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        // 1m & 1h LRC colour (the REAL candle rgb ● swatch), aligned on = and bordered by | :  tf = ● zone | likely move
                        LrcTfLine("1m", ind.hue, ind.pos, ind.col)
                        r.ind1h?.let { LrcTfLine("1h", it.hue, it.pos, it.col) }
                    }
                    Text("▁ ${fmtPrice(sp(ind.lower))}", color = C.muted, fontFamily = MonoNum, fontSize = 9.sp)
                }
            }
            // 🕯 _QT_LRC_1M_1H_MEAN_2026_10_23 — below the 1m | 1h colours: what the same/different colour means for the move (current candles), same read as QT
            r.lrcMean?.text?.let { txt ->
                Spacer(Modifier.height(5.dp))
                Text(txt, color = lrcMeanColor(r.lrcMean.tone), fontSize = 10.sp, softWrap = true, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── plan: every row aligned on = like the ChartWire desk ──
        SectionLabel("PLAN")
        PlanRow("side", "${sideArrow(r.side)} ${r.side ?: "—"}", sideColor(r.side))
        r.entry?.let { PlanRow("now", fmtPrice(sp(it)), C.text) }
        r.stop?.let { s ->
            val tag = if (s.green) (if (s.pays) "🟩 pays" else "🟩") else "🟧"
            val atr = s.atrAway?.let { " ·${it.fmt1()}×ATR" } ?: ""
            PlanRow("SL", "$tag ${fmtPrice(sp(s.price))}$atr", if (s.green) C.up else C.gold)
            // 📊 _QT_WATCH_HIT_ODDS_OWN_ROW_2026_10_22 — the "chance it gets hit" (reach_pct, history magnet) sits on its OWN row with a
            // 3-char label and a tiny value ("📊 SL 58%") directly under the level it belongs to. A combined "SL n% · TP3 n%" line was
            // still too wide and the round watch clipped the TP3 tail; a value this short can NEVER be clipped.
            s.reachPct?.let { PlanRow("hit", "📊 SL $it%", C.muted) }
            s.rr?.let { PlanRow("RR", "%.2f".format(it), rrColor(it)) }
        }
        r.target?.let { t ->
            val src = t.from?.let { " ·$it" } ?: ""
            PlanRow("TP3", "📈 ${fmtPrice(sp(t.price))}$src", C.text)
            t.reachPct?.let { PlanRow("hit", "📊 TP3 $it%", C.muted) }   // 📊 chance price REACHES TP3 — own short row, never clipped
            if (r.stop?.rr == null) t.rr?.let { PlanRow("RR", "%.2f".format(it), rrColor(it)) }
        }
        // 🎞 _QT_WATCH_1H_TAPE_2026_10_22 — below the hit rows: the 1h tape (who hit harder) + a plain "means", in sync with the QT candle card
        r.tape?.let { tp ->
            if (tp.buy != null && tp.sell != null) PlanRow("tape", "buyers ${tp.buy}% · sellers ${tp.sell}%", C.text)
            tp.means?.let { PlanRow("means", it, C.muted) }
        }
        r.backup?.let { b ->
            // 📏 _QT_ORANGE_SL_ATR_2026_10_22 — the 🟧 2nd SL shows its ×ATR distance too, exactly like the 🟩 green SL (the main orange SL
            // already does via the SL row). So every stop on the watch — green or orange, main or backup — reads its distance in ATR.
            val atr = b.atrAway?.let { " ·${it.fmt1()}×ATR" } ?: ""
            PlanRow("2nd SL", "🟧 ${fmtPrice(sp(b.price))}$atr", C.gold)
        }

        // ── why it isn't a trade yet ──
        r.reason?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }

        // 🚦 _QT_WATCH_SIGNAL_CARD_2026_10_23 — the SAME traffic light + reasons the QT watchlist / ranked dot shows (its colour and the
        // "why" the server scored), bordered in that colour, right above the open-on-phone button so the read is at a glance on the watch.
        Spacer(Modifier.height(12.dp))
        SignalCard(r)

        // 🔥 _QT_WATCH_HEAT_SECTION_2026_10_23 — the market-heat breakdown, auto-collapsed, right above the open-on-phone button: 1m + 1h
        // heat with the ↗/↘ trend, the 1m×1h synergy tag, and (when opened) the four driver bars on both clocks — the SAME QT heat card read.
        r.heat?.let {
            Spacer(Modifier.height(10.dp))
            HeatSection(it)
        }

        // 📱 hold 3s → open THIS asset on the phone's QT
        Spacer(Modifier.height(10.dp))
        HoldToPhoneButton(r.symbol, r.asset)
        // 🖲 hold 5s → this asset's desk side on the picked broker (signed by the watch's own key) — the very bottom of the card
        Spacer(Modifier.height(8.dp))
        OrderHoldButton(r, brokerName, wo, onChanged)
        Spacer(Modifier.height(18.dp))
    }
}

// 🚦 The traffic light + its reasons, bordered in the light's colour — the watch twin of the QT watchlist / ranked dot pop-up.
// entry mode = Sam is flat (take it?) · exit mode = Sam holds the asset (hold / manage / close). Same heads + reasons as siglight.js.
@Composable
private fun SignalCard(r: Row) {
    val sig = r.signal
    if (sig != "green" && sig != "yellow" && sig != "red") return
    val exit = r.signalMode == "exit"
    val col = when (sig) { "green" -> C.up; "yellow" -> C.yellow; else -> C.down }
    val dot = when (sig) { "green" -> "🟢"; "yellow" -> "🟡"; else -> "🔴" }
    val head = if (exit) when (sig) {
        "green" -> "HOLD — trade working"; "yellow" -> "MANAGE — tighten / take some"; else -> "CLOSE NOW"
    } else when (sig) {
        "green" -> "TAKE IT — press-worthy"; "yellow" -> "ALMOST — one trigger away"; else -> "FAR — stand aside"
    }
    val reasons = r.signalWhy?.split(" · ")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(C.panel)
            .border(1.dp, col, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dot, fontSize = 12.sp)
            Spacer(Modifier.width(5.dp))
            Text(head, color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold, softWrap = true, modifier = Modifier.weight(1f))
        }
        if (reasons.isEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text("no setup read", color = C.muted, fontSize = 10.sp)
        } else reasons.forEach {
            Spacer(Modifier.height(3.dp))
            Text("• $it", color = C.text, fontSize = 10.sp, softWrap = true)
        }
    }
}

// 🤝 _QT_KURU_WAE_VERDICT_2026_10_23 — the KURU + WAE verdict exactly as the QT chart banner reads it: 📊 KURU (pressure) first, 🌋 WAE
// (energy), then → price. Tone colours MATCH the chart banner (.pb-rel) + the QT watchlist (.wl-kw) exactly, so all three surfaces agree:
// up = green breakout · dn = red · ok = cyan (mild agreement, NOT a breakout — distinct from up) · warn = gold · dead = muted. Same words (shared kuruwae.js).
@Composable
private fun KwVerdictCard(kv: KwVerdict) {
    val col = when (kv.tone) { "up" -> C.up; "dn" -> C.down; "ok" -> C.aqua; "warn" -> C.gold; else -> C.muted }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(C.panel)
            .border(1.dp, col.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        kv.kuru?.let { Text("📊 KURU $it", color = C.text, fontSize = 10.sp, softWrap = true) }
        kv.wae?.let { Text("🌋 WAE $it", color = C.text, fontSize = 10.sp, softWrap = true) }
        kv.price?.let {
            Spacer(Modifier.height(2.dp))
            Text("→ price $it", color = col, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, softWrap = true)
        }
    }
}

// Press-and-HOLD 3 s (the shared HoldBar); on completion the OPEN phone QT switches to this asset in place — no browser, no reload.
@Composable
private fun HoldToPhoneButton(symbol: String?, assetName: String) {
    if (symbol == null) return
    val ctx = LocalContext.current
    HoldBar(3, "📱 hold 3s → open $assetName on phone", C.accent) {
        val ok = Net.postFocus(symbol)     // set the FRESH focus first…
        if (ok) openOnPhone(ctx)           // …then OPEN the app — a cold open reads the fresh focus and lands on THIS asset; an already-open app switches in place
        Toast.makeText(ctx, if (ok) "$assetName → opening on phone" else "Couldn't reach the server", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SectionLabel(s: String) {
    Text(s, color = C.muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    Spacer(Modifier.height(2.dp))
}

// ── PAGE 0: the broker picker. Tap one; every asset page re-prices to it. Default = Chart. ──
@Composable
private fun BrokerPicker(brokers: List<Broker>, selected: String, onPick: (String) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).bezelScroll(scroll).padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("🏦 Broker", color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("pick one — every asset follows it", color = C.muted, fontSize = 9.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        BrokerRow("Chart", "the engine / chart price", selected == "Chart") { onPick("Chart") }
        brokers.forEach { b ->
            val sub = (if (b.basis == null) "no gap yet · = chart"
                       else "${if (b.basis >= 0) "+" else ""}${b.basis.fmt2()} vs chart") + (b.mode?.let { " · $it" } ?: "")
            BrokerRow(b.name, sub, selected == b.name) { onPick(b.name) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BrokerRow(name: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) C.accent.copy(alpha = 0.22f) else C.panel)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text((if (on) "● " else "") + name, color = if (on) C.accent else C.text, fontSize = 12.sp, maxLines = 1)
        Text(sub, color = C.muted, fontSize = 9.sp, maxLines = 1)
    }
}

// One LRC timeframe line:  1m = ● mid | coiling, no edge — wait   (aligned on =, bordered by |)
@Composable
private fun LrcTfLine(tf: String, hue: String?, pos: Double?, col: IntArray?) {
    val c = rgbColor(col, hue)   // the EXACT candle colour — every variation, not just red/green
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tf, color = C.muted, fontSize = 9.sp, fontFamily = MonoNum,
            textAlign = TextAlign.End, modifier = Modifier.width(18.dp))
        Text(" = ", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum)
        Text("● ${lrcZone(hue, pos)}", color = c, fontSize = 9.sp, maxLines = 1)
        Text(" | ", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum)
        Text(lrcMeaning(hue), color = C.muted, fontSize = 9.sp, maxLines = 1)
    }
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
        // 📐 _QT_PLANROW_WRAPS_2026_10_22 — constrain the value to the remaining row width and WRAP with NO line cap, so a long "means"
        // sentence (e.g. "sellers won but stretched down — a bounce is common") shows IN FULL. A maxLines=2 cap clipped it at "wait for";
        // unbounded + maxLines 1 overran the screen edge. Unlimited wrap = every value, every variant, complete — never cut off.
        Text(value, color = valueColor, fontSize = 11.sp, fontFamily = MonoNum,
            softWrap = true, modifier = Modifier.weight(1f))
    }
}

// 🚩 _QT_WATCH_TOP_BANNERS_2026_10_22 — banners on top of the asset page, in sync with the QT data: 🌙 the market is CLOSED (so the page is its
// last snapshot, not live) · ⏸ the BOT is stood down (no account is awake ⇒ it will NOT auto-take — manual only) · 📅 an imminent econ event
// (red when high-impact — a fresh entry is worse right before one). Zero, one or several can stack.
@Composable
private fun TopBanners(r: Row) {
    val stoodDown = r.brokers.isNotEmpty() && r.brokers.none { it.mode == "woken" || it.mode == "managing" }
    if (r.closed) Banner("🌙 MARKET CLOSED · last snapshot", C.muted)
    if (stoodDown) Banner("⏸ BOT STOOD DOWN · manual only", C.gold)
    r.event?.let { e ->
        val hi = e.impact?.contains("high", ignoreCase = true) == true
        val whenTxt = e.mins?.let { if (it <= 0) "now" else "${it}m" } ?: ""
        Banner("📅 ${e.title ?: "event"}${if (whenTxt.isNotEmpty()) " · $whenTxt" else ""}", if (hi) C.down else C.explosion)
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, softWrap = true)
    }
}

// 📋 _QT_WATCH_WATCHLIST_PAGE_2026_10_22 — the whole board at a glance, the SAME format as the QT watchlist and in sync (one feed): per asset
// the ticker (tinted by its signal) + side, the price in the picked broker, and the 1m | 1h LRC candle colour (the exact rgb) with its zone —
// the identical "1m ● zone | 1h ● zone" the QT watchlist and the asset pages draw. Swipe right from Trades to reach it.
@Composable
private fun WatchlistPage(rows: List<Row>, broker: String, onPick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).bezelScroll(scroll).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))   // clear the TimeText
        Text("WATCHLIST", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("in sync with QT · tap an asset to open it", color = C.muted, fontSize = 9.sp)
        Spacer(Modifier.height(6.dp))
        // 🗂 _QT_WATCH_WATCHLIST_ORDER_MATCHES_QT_2026_10_23 — display in the SAME order as the QT watchlist ("closest to setup first", the
        // desk lrc_order the server tags as `rank`); rows with no rank sort last. Tap still navigates by the ORIGINAL feed index, so the asset
        // swipe pages stay put while only this list re-sorts — exactly like QT.
        val ordered = rows.withIndex().sortedWith(compareBy({ it.value.rank ?: Int.MAX_VALUE }, { it.index }))
        for (iv in ordered) {
            WatchlistRow(iv.value, broker) { onPick(iv.index) }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WatchlistRow(r: Row, broker: String, onClick: () -> Unit) {
    val basis = if (broker == "Chart") 0.0 else (r.brokers.firstOrNull { it.name == broker }?.basis ?: 0.0)
    val px = (r.entry ?: r.ind?.px)?.let { it + basis }
    Column(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(r.asset + if (r.closed) " 🌙" else "", color = callColor(r.signal), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
            Text(sideArrow(r.side), color = sideColor(r.side), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(fmtPrice(px), color = C.text, fontFamily = MonoNum, fontSize = 12.sp)
        }
        Spacer(Modifier.height(2.dp))
        // 📊 _QT_WL_ALIGNED_GRID_2026_10_23 — Sam: "include its legs just like qt pc does it" + "organize and align all". One tidy two-column
        // grid so every row lines up:  legs meter │ WAE  ·  1m LRC │ 1h LRC  ·  N short │ ✈️ far. Same data + wording as the QT watchlist.
        val shortN = r.call?.let { Regex("(\\d+)\\s*short").find(it)?.groupValues?.getOrNull(1) }
            ?: run { val li = r.lit; val le = r.legs; if (li != null && le != null && le > li) (le - li).toString() else null }
        WlGrid(
            left = { LegsInline(r.lit, r.legs) },
            right = { r.wae?.let { WaeInline(it) } },
        )
        WlGrid(
            left = { r.ind?.let { Text("●", color = rgbColor(it.col, it.hue), fontSize = 9.sp); Text(" 1m ${lrcZone(it.hue, it.pos)}", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1) } },
            right = { r.ind1h?.let { Text("●", color = rgbColor(it.col, it.hue), fontSize = 9.sp); Text(" 1h ${lrcZone(it.hue, it.pos)}", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1) } },
        )
        if (shortN != null || !r.far.isNullOrBlank()) WlGrid(
            left = { shortN?.let { Text("$it short", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum) } },
            right = { r.far?.takeIf { it.isNotBlank() }?.let { Text("✈️ $it", color = C.explosion, fontSize = 9.sp, fontFamily = MonoNum) } },
        )
        // 🛑 _QT_WL_STOP_2026_10_23 — the desk stop on one aligned line, in the SELECTED broker's price (basis): 🟩 green (a stop that pays) /
        // 🟧 orange (2nd/backup SL) · SL price on the left · RR · ×ATR away on the right. Same stop the QT watchlist + chart + asset page show.
        // 🎨 _QT_WL_STOP_COLOR_2026_10_23 — SL green when it's a paying 🟩 stop (clears the 1.3R threshold), red when not; RR green ≥1.3R, red below; ×ATR muted
        r.stop?.takeIf { !r.closed && it.price != null }?.let { st ->
            // 🎨 green = passes the 1.3R threshold (SL + RR green together, same single rule as the QT watchlist); fall back to the paying-stop flag only when RR is missing
            val slCol = if (st.rr != null) { if (st.rr >= 1.3) C.up else C.down } else { if (st.pays) C.up else C.down }
            WlGrid(
                left = { Text("${if (st.green) "🟩" else "🟧"} SL ${fmtPrice(st.price?.plus(basis))}", color = slCol, fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1) },
                right = {
                    st.rr?.let { rr -> Text("%.2f".format(rr) + "R", color = if (rr >= 1.3) C.up else C.down, fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1) }
                    st.atrAway?.let { a -> if (st.rr != null) Text(" · ", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum); Text("${a.fmt1()}×ATR", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1) }
                },
            )
        }
        // 🗓 the option-dealer day type, same tag as the QT watchlist (🏃 trend · ↔️ range · ⚖️ mixed) — its own aligned line
        r.gammaDay?.let { if (dayText(it) != null) WlGrid(left = { DayTag(it) }, right = {}) }
        // 🔥 _QT_HEAT_ON_EVERY_ROW_2026_10_23 — the 1m | 1h market heat with its ↗/↘ trend (same read QT's watchlist shows), one aligned line
        r.heat?.let { ht ->
            if (ht.m != null || ht.h != null) WlGrid(
                left = { HeatInline("1m", ht.m) },
                right = { HeatInline("1h", ht.h) },
            )
        }
    }
}

// 🗓 the option-dealer day type, same words + icons as the QT chip. 🏃 trend day (moves extend) · ↔️ range day (moves fade) · ⚖️ mixed (too close to the flip to tell).
@Composable
private fun DayTag(day: String?) {
    val txt = dayText(day) ?: return
    Text(txt, color = dayColor(day), fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1)
}
private fun dayText(day: String?): String? = when (day) { "trend" -> "🏃 Trend day"; "range" -> "↔️ Range day"; "mixed" -> "⚖️ Mixed"; else -> null }
private fun dayColor(day: String?): Color = when (day) { "trend" -> C.up; "range" -> C.gold; else -> C.muted }

// 📊 one aligned two-column line of the watchlist row — the left and right halves each take exactly half so the columns line up down the list.
@Composable
private fun WlGrid(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { left() }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { right() }
    }
}

// the desk-legs meter, exactly like QT's: one tick per leg, the lit ones filled (green when ALL lit, else accent) + the lit/legs count.
@Composable
private fun LegsInline(lit: Int?, legs: Int?) {
    if (legs == null || legs <= 0) return
    val l = (lit ?: 0).coerceIn(0, legs)
    val onCol = if (l >= legs) C.up else C.accent
    for (i in 0 until legs) {
        Box(Modifier.padding(end = 1.dp).width(4.dp).height(6.dp).clip(RoundedCornerShape(1.dp)).background(if (i < l) onCol else C.border))
    }
    Spacer(Modifier.width(3.dp))
    Text("$l/$legs", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum, fontWeight = FontWeight.SemiBold)
}

// the WAE read, same variants + wording as the QT chip: ✅ active · 🪶 lite · ⏸ pause · 🥊 against · 🟠 fading, tinted by the mode.
@Composable
private fun WaeInline(w: Wae) {
    // 🟥🟩 _QT_WAE_ACTIVE_CHECK_BY_SIDE_2026_10_23 — Sam: "wae active should have red checkmark if its active red and green for vice
    // versa". The ✔ active check now follows the WAE's OWN side — green when it is firing a buy / up, red when sell / down (it used to
    // always be green). The other states keep their own colour. Same read as the QT watchlist chip.
    val m = w.mode?.lowercase()
    val active = m == "active" || m == "firing" || m == "fire"
    val wc = when {
        active -> if (w.side?.lowercase() == "sell") C.down else C.up
        m == "against" -> C.down
        m == "lite" -> C.accent
        m == "fading" || m == "dying" || m == "spent" -> C.gold
        else -> C.muted
    }
    Text(waeMode(w.mode, w.dying), color = wc, fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1)
}

// 🔥 _QT_HEAT_ON_EVERY_ROW_2026_10_23 — the temperature bar, the SAME glass-liquid gradient as QT's heat meter (aqua→green→yellow→orange→
// red): the fill reaches the 0-100 value and the colour AT THE TIP is how hot it is — the identical scale on every bar, over a thin track.
@Composable
private fun HeatBar(value: Int?, modifier: Modifier = Modifier) {
    val v = (value ?: 0).coerceIn(0, 100)
    Canvas(modifier.height(6.dp)) {
        val w = size.width; val h = size.height; val r = CornerRadius(h / 2f, h / 2f)
        drawRoundRect(color = C.border, cornerRadius = r)
        if (v > 0 && w > 0f) {
            val fw = (w * v / 100f).coerceAtLeast(h)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(Color(0xFF22D3EE), Color(0xFF34D399), Color(0xFFFACC15), Color(0xFFFB923C), Color(0xFFEF4444)),
                    startX = 0f, endX = w,
                ),
                size = Size(fw, h), cornerRadius = r,
            )
        }
    }
}

private fun heatArrow(trend: String?): String = when (trend) { "heating" -> "↗"; "cooling" -> "↘"; else -> "" }
// 🕯🎨 the 1m-vs-1h LRC meaning tone → colour by ALIGNMENT (Sam): aligned = green · opposed = red · coiling = yellow (whichever way price goes)
private fun lrcMeanColor(tone: String?): Color = when (tone) { "aligned" -> C.up; "oppose" -> C.down; "coil" -> C.yellow; else -> C.muted }
// tone word → colour (both tier words cold/warming/hot/boiling and tag words aligned/spike/pause/building/stirring/simmer/quiet)
private fun heatToneColor(word: String?): Color = when (word?.lowercase()) {
    "boiling" -> C.down
    "hot", "aligned", "spike" -> C.lrcOrange
    "warming", "building", "stirring", "simmer", "pause" -> C.gold
    else -> C.muted
}

// one compact "1m [bar] 78↗" cell — used on the watchlist row + the collapsed heat header
@Composable
private fun HeatInline(tf: String, t: HeatTier?) {
    Text(tf, color = C.muted, fontSize = 9.sp, fontFamily = MonoNum)
    Spacer(Modifier.width(3.dp))
    if (t?.v == null) { Text("·", color = C.muted, fontSize = 9.sp); return }
    t.icon?.let { Text(it, fontSize = 9.sp); Spacer(Modifier.width(2.dp)) }   // 🔥 the heat emoji (🌫️/❄️/🔥/🌋) per clock
    HeatBar(t.v, Modifier.width(20.dp))
    Spacer(Modifier.width(3.dp))
    Text("${t.v}${heatArrow(t.trend)}", color = C.text, fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1)
}

// 🔥 _QT_WATCH_HEAT_SECTION_2026_10_23 — Sam: "above hold for 3 second to open app make the 1h 1m auto collapsed section … 1m 1h kuru
// pressure wae energy volume pace move size temperature colored bar graphic just like qt heat". Auto-collapsed (tap to open) — the SAME
// market heat (lib/heat.js) as QT's heat card: 1m + 1h heat with the ↗/↘ trend, the 1m×1h synergy tag, and the four driver bars
// (📊 KURU pressure · 🌋 WAE energy · 🫧 volume pace · 📏 move size) on both clocks, each a temperature-coloured liquid bar.
@Composable
private fun HeatSection(ht: Heat) {
    if (ht.m == null && ht.h == null) return
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(C.panel)
            .border(1.dp, C.border, RoundedCornerShape(10.dp))
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        // header — always visible: 🔥 HEAT … tag … ▸/▾
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("HEAT", color = C.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            ht.tagEmoji?.let { e -> Text(e + (ht.tagWord ?: ""), color = heatToneColor(ht.tagWord), fontSize = 9.sp, maxLines = 1) }
            Spacer(Modifier.width(5.dp))
            Text(if (open) "▾" else "▸", color = C.muted, fontSize = 10.sp)
        }
        if (!open) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeatInline("1m", ht.m)
                Spacer(Modifier.width(12.dp))
                HeatInline("1h", ht.h)
            }
        } else {
            Spacer(Modifier.height(6.dp))
            HeatTierRow("1m", ht.m)
            HeatTierRow("1h", ht.h)
            ht.tagEmoji?.let { e ->
                Spacer(Modifier.height(4.dp))
                Text("$e ${ht.tagWord ?: ""}", color = heatToneColor(ht.tagWord), fontSize = 10.sp, maxLines = 1)
            }
            ht.parts?.let { p ->
                Spacer(Modifier.height(7.dp))
                Text("1m — what's driving it", color = C.muted, fontSize = 9.sp)
                HeatPartRow("📊 KURU pressure", p.pressure)
                HeatPartRow("🌋 WAE energy", p.energy)
                HeatPartRow("🫧 volume pace", p.volume)
                HeatPartRow("📏 move size", p.range)
            }
            ht.partsH?.let { p ->
                Spacer(Modifier.height(6.dp))
                Text("1h — what's driving it", color = C.muted, fontSize = 9.sp)
                HeatPartRow("📊 KURU pressure", p.pressure)
                HeatPartRow("🌋 WAE energy", p.energy)
                HeatPartRow("🫧 volume pace", p.volume)
                HeatPartRow("📏 move size", p.range)
            }
        }
    }
}

// one "1m [=====] 78↗ hot" line — the heat on one clock with its trend + word
@Composable
private fun HeatTierRow(tf: String, t: HeatTier?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tf, color = C.muted, fontSize = 10.sp, fontFamily = MonoNum, textAlign = TextAlign.End, modifier = Modifier.width(20.dp))
        Spacer(Modifier.width(6.dp))
        if (t?.v == null) { Text("—", color = C.muted, fontSize = 10.sp); return@Row }
        t.icon?.let { Text(it, fontSize = 10.sp); Spacer(Modifier.width(4.dp)) }   // 🔥 the heat emoji (🌫️/❄️/🔥/🌋) per clock
        HeatBar(t.v, Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text("${t.v}${heatArrow(t.trend)}", color = C.text, fontSize = 10.sp, fontFamily = MonoNum, maxLines = 1, modifier = Modifier.width(34.dp))
        t.word?.let { Text(it, color = heatToneColor(it), fontSize = 9.sp, maxLines = 1) }
    }
}

// one driver bar — "📊 KURU pressure [=====] 71" — the component graded 0-100 vs this asset's own day
@Composable
private fun HeatPartRow(label: String, v: Int?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.muted, fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        HeatBar(v, Modifier.width(58.dp))
        Spacer(Modifier.width(5.dp))
        Text((v ?: 0).toString(), color = C.text, fontSize = 9.sp, fontFamily = MonoNum, textAlign = TextAlign.End, modifier = Modifier.width(22.dp))
    }
}

// 🔥 _QT_WATCH_MARKET_HEAT_PAGE_2026_10_23 — Sam: "include a market heat page before watchlist … see all assets heat and expand to see
// their breakdown for both 1m 1h … auto collapse so i only see each asset respective 1m 1h bar". One scrollable board: every asset with
// its 1m + 1h temperature bar always visible; tap an asset to expand the 1m & 1h driver breakdown (📊 KURU · 🌋 WAE · 🫧 volume · 📏 move).
// Hottest (1m) first — a true market-heat ranking. Same heat (lib/heat.js) as QT's heat card and every watch heat surface (r.heat, ONE source).
@Composable
private fun MarketHeatPage(rows: List<Row>, goToAsset: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).bezelScroll(scroll).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(22.dp))   // clear the TimeText
        Text("🔥 MARKET HEAT", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("hottest 1h first · tap an asset for its 1m · 1h breakdown", color = C.muted, fontSize = 9.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        // 🔥 _QT_WATCH_HEAT_RANK_1H_2026_10_23 — Sam: "make the watch ranking always be default stuck to the 1h ranking for market heat".
        // Hottest 1h heat first (nulls last); navigation uses the ORIGINAL feed index so the asset swipe pages stay put.
        val ordered = rows.withIndex().sortedWith(compareByDescending { it.value.heat?.h?.v ?: -1 })
        for (iv in ordered) {
            MarketHeatRow(iv.value) { goToAsset(iv.index) }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(14.dp))
    }
}

// one asset on the heat board: ticker + its tag, the 1m & 1h bars always shown; tap to expand the driver breakdown for BOTH clocks.
@Composable
private fun MarketHeatRow(r: Row, onOpen: () -> Unit) {
    val ht = r.heat
    var open by remember { mutableStateOf(false) }   // auto-collapsed — only the 1m/1h bars until tapped
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(C.panel)
            .border(1.dp, C.border, RoundedCornerShape(10.dp))
            .clickable { open = !open }
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(r.asset + if (r.closed) " 🌙" else "", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            ht?.tagEmoji?.let { e -> Text(e + (ht.tagWord ?: ""), color = heatToneColor(ht.tagWord), fontSize = 9.sp, maxLines = 1); Spacer(Modifier.width(5.dp)) }
            Text(if (open) "▾" else "▸", color = C.muted, fontSize = 10.sp)
        }
        if (ht?.m == null && ht?.h == null) {
            Text("no heat yet", color = C.muted, fontSize = 9.sp)
            return@Column
        }
        Spacer(Modifier.height(3.dp))
        HeatTierRow("1m", ht.m)
        HeatTierRow("1h", ht.h)
        // 🕯 _QT_WATCH_HEAT_LRC_ZONES_2026_10_23 — Sam: "below the 1m 1h heat data also include 1m 1h broke down broke up and all its
        // variants just like its done on watchlist … organized aligned". The SAME ● 1m / 1h LRC zone words the watchlist row shows
        // (broke↑ · top · upper · mid · lower · bottom · broke↓), each in its exact candle colour, 1m left and 1h right on one aligned row.
        if (r.ind != null || r.ind1h != null) {
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                r.ind?.let { Text("● 1m ${lrcZone(it.hue, it.pos)}", color = rgbColor(it.col, it.hue), fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1) }
                Spacer(Modifier.weight(1f))
                r.ind1h?.let { Text("● 1h ${lrcZone(it.hue, it.pos)}", color = rgbColor(it.col, it.hue), fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1) }
            }
        }
        if (open) {
            ht.parts?.let { p ->
                Spacer(Modifier.height(6.dp))
                Text("1m — what's driving it", color = C.muted, fontSize = 9.sp)
                HeatPartRow("📊 KURU pressure", p.pressure)
                HeatPartRow("🌋 WAE energy", p.energy)
                HeatPartRow("🫧 volume pace", p.volume)
                HeatPartRow("📏 move size", p.range)
            }
            ht.partsH?.let { p ->
                Spacer(Modifier.height(5.dp))
                Text("1h — what's driving it", color = C.muted, fontSize = 9.sp)
                HeatPartRow("📊 KURU pressure", p.pressure)
                HeatPartRow("🌋 WAE energy", p.energy)
                HeatPartRow("🫧 volume pace", p.volume)
                HeatPartRow("📏 move size", p.range)
            }
            Spacer(Modifier.height(5.dp))
            Text("open ${r.asset} →", color = C.accent, fontSize = 9.sp, modifier = Modifier.clickable { onOpen() })
        }
    }
}

@Composable
private fun HubPage(count: Int, updatedAt: Long, error: String?) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).bezelScroll(scroll).padding(18.dp),
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

// Opens QT on the PHONE (not the desktop): send the remote-open intent to the NEARBY phone node specifically.
// If QT is installed as an app/PWA on the phone it opens there; otherwise the phone's browser (never the desktop).
private fun openOnPhone(ctx: android.content.Context) {
    val helper = RemoteActivityHelper(ctx)
    val intent = Intent(Intent.ACTION_VIEW)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setData(Uri.parse(BuildConfig.PHONE_URL))
    fun fire(nodeId: String?) {
        try {
            val future = helper.startRemoteActivity(intent, nodeId)
            future.addListener({
                val ok = try { future.get(); true } catch (e: Exception) { false }
                Toast.makeText(ctx, if (ok) "Opening on your phone" else "Couldn't reach the phone", Toast.LENGTH_SHORT).show()
            }, ContextCompat.getMainExecutor(ctx))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't open on the phone", Toast.LENGTH_SHORT).show()
        }
    }
    try {
        Wearable.getNodeClient(ctx).connectedNodes
            .addOnSuccessListener { nodes ->
                val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                fire(phone?.id)
            }
            .addOnFailureListener { fire(null) }
    } catch (e: Exception) {
        fire(null)
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
// 🎨 _QT_BUTTON_RR_COLOR_2026_10_23 — Sam: "red and green is passes threshold … apply accordingly to watch". Every R on the watch now reads
// GREEN when it clears the 1.3R desk threshold, RED below — the SAME binary rule as the QT button, watchlist SL/RR and the chart's box R.
// (Was 3-tier green/gold/red, which showed the same R green in the stop line but gold in the plan row — now consistent everywhere.)
private fun rrColor(rr: Double): Color = if (rr >= 1.3) C.up else C.down
private fun dots(idx: Int, total: Int): String {
    val sb = StringBuilder()
    for (i in 0 until total) sb.append(if (i == idx) "●" else "·")
    return sb.toString()
}
private fun Double.fmt1(): String = "%.1f".format(this)
private fun Double.fmt2(): String = "%.2f".format(this)

private fun freshness(updatedAt: Long, error: String?): String {
    if (error != null) return error
    if (updatedAt == 0L) return "Loading…"
    val s = ((System.currentTimeMillis() - updatedAt) / 1000).toInt()
    return if (s <= 1) "updated just now" else "updated ${s}s ago"
}
