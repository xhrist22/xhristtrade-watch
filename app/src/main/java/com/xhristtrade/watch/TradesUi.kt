package com.xhristtrade.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ⌚🔐 _QT_WATCH_ORDERS_SIGNED_2026_10_22 — the watch's trade screens: the Trades page (pair · every open trade live · hold to close) and
// the hold-5s order bar at the bottom of each asset card. Nothing here decides a trade: the server checks the signature, the desk side,
// the account and its limits, and the account's own bot places / closes it in its broker's prices.

/** What the trade calls know right now, shared by every page. */
data class WoUi(val paired: Boolean?, val feed: TradesFeed?, val error: String?)

/**
 * Press-and-HOLD for [seconds] (a bar fills, the label counts down); letting go early sends nothing. While [busy] the bar shows the
 * work in progress and ignores presses. [onDone] runs once per completed hold.
 */
@Composable
fun HoldBar(seconds: Int, idle: String, tint: Color, enabled: Boolean = true, onDone: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var prog by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    val ms = seconds * 1000f
    Box(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (enabled) C.panel else C.panel.copy(alpha = 0.5f))
            .pointerInput(enabled, idle) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    if (busy) return@detectTapGestures
                    val start = System.currentTimeMillis()
                    val job = scope.launch {
                        while (true) {
                            prog = ((System.currentTimeMillis() - start) / ms).coerceAtMost(1f)
                            if (prog >= 1f) { busy = true; prog = 0f; try { onDone() } finally { busy = false }; break }
                            delay(30)
                        }
                    }
                    tryAwaitRelease()
                    if (!busy) { job.cancel(); prog = 0f }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (prog > 0f) Box(Modifier.fillMaxWidth(prog).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha = 0.55f)))
        Text(
            when { busy -> "sending…"; prog > 0f -> "keep holding… ${(seconds - prog * seconds).toInt().coerceAtLeast(0) + 1}s"; else -> idle },
            color = if (enabled) C.text else C.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

// ── PAGE 1: the trades ──
@Composable
fun TradesPage(wo: WoUi, onChanged: () -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).bezelScroll(scroll).padding(horizontal = 14.dp),   // 🧭 bezel scrolls this page
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("📒 Trades", color = C.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        when {
            wo.paired == null -> Text(wo.error ?: "checking…", color = C.muted, fontSize = 10.sp, textAlign = TextAlign.Center)
            wo.paired == false -> PairPanel(onChanged)
            else -> {
                val f = wo.feed
                if (f?.off == true) Text("⛔ watch orders are OFF on the server", color = C.gold, fontSize = 9.sp, textAlign = TextAlign.Center)
                wo.error?.let { Text(it, color = C.down, fontSize = 9.sp, textAlign = TextAlign.Center) }
                // 🖲✖ in-flight actions across every account, up top (transient)
                f?.accounts?.forEach { a ->
                    a.order?.let { o -> Text("🖲 ${o.side ?: ""} ${o.asset ?: ""} · ${o.status ?: ""}${o.why?.let { " · $it" } ?: ""}",
                        color = statusColor(o.status), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 3) }
                    a.closing?.let { c -> Text("✖ ${c.asset ?: ""} · ${c.status ?: ""}${c.why?.let { " · $it" } ?: ""}",
                        color = statusColor(c.status), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 3) }
                }
                // 📒 _QT_WATCH_TRADES_BY_ASSET_2026_10_23 — every open trade grouped by ASSET and AUTO-COLLAPSED: one tappable header per asset
                // (its side · how many accounts hold it · the net P&L); tap to expand to the per-account cards, each with its own ✖ close.
                val groups = f?.accounts.orEmpty().flatMap { a -> a.trades.map { a to it } }.groupBy { it.second.asset }
                if (groups.isEmpty()) {
                    Spacer(Modifier.height(10.dp)); Text("no open trades", color = C.muted, fontSize = 11.sp)
                } else {
                    val flat = groups.values.flatten()
                    val net = flat.sumOf { it.second.pnl ?: 0.0 }; val n = flat.size
                    Spacer(Modifier.height(4.dp))
                    Text("open ${signedMoney(net)} · $n ${if (n == 1) "trade" else "trades"} · ${groups.size} ${if (groups.size == 1) "asset" else "assets"}",
                        color = pnlColor(net), fontFamily = MonoNum, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    groups.forEach { (asset, list) -> AssetGroup(asset, list, onChanged) }
                }
                // 📆 _QT_WATCH_TRADES_24H_2026_10_23 — the day's CLOSED trades (last 24 h), held so the page keeps the record after a trade closes
                DoneSection(f?.done.orEmpty())
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PairPanel(onChanged: () -> Unit) {
    var note by remember { mutableStateOf<String?>(null) }
    val code = remember { try { Signer.pairCode() } catch (e: Exception) { "—" } }
    Spacer(Modifier.height(6.dp))
    Text("🔐 This watch is not trusted with orders yet", color = C.gold, fontSize = 10.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(4.dp))
    Text(code, color = C.text, fontFamily = MonoNum, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("1 · hold below to ask\n2 · on your phone QT a ⌚ card shows a code\n3 · approve ONLY if it is this exact code",
        color = C.muted, fontSize = 9.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    HoldBar(3, "🔐 hold 3s → ask to pair", C.accent) {
        note = when (val r = Orders.pair()) {
            is WoResult.Ok -> if (r.json.optBoolean("approved")) "already approved ✅" else "asked — approve on your phone's QT"
            is WoResult.Err -> r.why
            is WoResult.Unpaired -> r.why
        }
        onChanged()
    }
    note?.let { Text(it, color = C.muted, fontSize = 9.sp, textAlign = TextAlign.Center) }
}

// 📒 _QT_WATCH_TRADES_BY_ASSET_2026_10_23 — one collapsed header per asset (side · ×accounts · net P&L); tap to open the per-account cards.
@Composable
private fun AssetGroup(asset: String, trades: List<Pair<Account, Trade>>, onChanged: () -> Unit) {
    var open by remember(asset) { mutableStateOf(false) }   // auto-collapsed
    val net = trades.sumOf { it.second.pnl ?: 0.0 }
    val dir = trades.firstOrNull()?.second?.dir
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel)
            .clickable { open = !open }.padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "▾" else "▸", color = C.muted, fontSize = 11.sp, modifier = Modifier.width(13.dp))
        Text("${if (dir == "SELL") "▼" else "▲"} $asset", color = if (dir == "SELL") C.down else C.up,
            fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(" ×${trades.size}", color = C.muted, fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Text(signedMoney(net), color = pnlColor(net), fontFamily = MonoNum, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    if (open) trades.forEach { (a, t) -> TradeCard(a, t, onChanged) }
}

@Composable
private fun TradeCard(a: Account, t: Trade, onChanged: () -> Unit) {
    val watchOnly = a.mode == "watch"
    Spacer(Modifier.height(4.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${a.name}${a.equity?.let { " · ${money(it)}" } ?: ""}", color = C.accent, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(t.pnl?.let { signedMoney(it) } ?: "?", color = pnlColor(t.pnl), fontFamily = MonoNum, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Line("lot", t.lot?.let { trimNum(it) } ?: "?", C.text)
        Line("in→now", "${fmtPrice(t.fill)} → ${fmtPrice(t.now)}", C.text)
        Line("R", t.r?.let { "%+.2f".format(it) } ?: "—", pnlColor(t.r))
        Line("SL", t.sl?.let { fmtPrice(it) + (t.lockedR?.let { r -> " ·locks ${"%+.2f".format(r)}R" } ?: "") } ?: "NOT SET", if (t.sl == null) C.gold else C.text)
        t.trailSl?.let { Line("🛡 trail", fmtPrice(it), C.gold) }
        Line("TP", t.tp?.let { fmtPrice(it) } ?: "—", C.text)
        Spacer(Modifier.height(4.dp))
        if (watchOnly) {
            Text("👁 watch-only — close it in MT5", color = C.muted, fontSize = 9.sp)
        } else {
            var res by remember(t.positionId) { mutableStateOf<String?>(null) }
            HoldBar(3, "✖ hold 3s → close ${t.asset} · ${a.name}", C.down) {
                res = when (val r = Orders.signed("close", mapOf("inst" to a.inst, "asset" to t.asset, "position_id" to t.positionId))) {
                    is WoResult.Ok -> "✖ close sent to ${a.name}"
                    is WoResult.Err -> "❌ ${r.why}"
                    is WoResult.Unpaired -> "🔐 ${r.why}"
                }
                onChanged()
            }
            res?.let { Text(it, color = C.muted, fontSize = 9.sp, textAlign = TextAlign.Center) }
        }
    }
}

/**
 * Bottom of each asset card: HOLD 5 s → this asset's desk side, on the broker picked on page 0, through QT's own order path. The press
 * names the account by its exact id AND name (from the signed roster) — the server refuses if they are not the same account.
 */
@Composable
fun OrderHoldButton(r: Row, broker: String, wo: WoUi, onChanged: () -> Unit) {
    val side = r.side?.uppercase()
    val acct = wo.feed?.roster?.firstOrNull { it.name == broker }
    val why = when {
        wo.paired != true -> "🔐 pair the watch on the Trades page"
        broker == "Chart" -> "🏦 pick a broker on page 1 to trade"
        acct == null -> "🏦 $broker isn't on the account list"
        side != "BUY" && side != "SELL" -> "no trade side on the desk"
        wo.feed?.off == true -> "⛔ watch orders are OFF"
        else -> null
    }
    var res by remember(r.asset, broker) { mutableStateOf<String?>(null) }
    val tint = if (side == "SELL") C.down else C.up
    val label = if (why != null) why else
        "${if (side == "SELL") "▼" else "▲"} hold 5s → $side ${r.asset}" + if (acct?.mode == "watch") " (ticket)" else ""
    HoldBar(5, label, tint, enabled = why == null) {
        res = when (val x = Orders.signed("order", mapOf("asset" to r.asset, "side" to side, "inst" to acct!!.inst, "broker" to acct.name))) {
            is WoResult.Ok -> describeOrder(x.json, acct.name)
            is WoResult.Err -> "❌ ${x.why}"
            is WoResult.Unpaired -> "🔐 ${x.why}"
        }
        onChanged()
    }
    res?.let { Text(it, color = C.muted, fontSize = 9.sp, textAlign = TextAlign.Center) }
}

// 🔄 _QT_MEAN_REVERSION_ORDER_2026_10_23 — the watch FADE button: take the OPPOSITE of the desk side with the run-origin stop (the server
// computes both). A deliberate 3-second hold, purple (the counter colour), 0.5 % and the bot trails it — the watch twin of QT's 🔄 button.
@Composable
fun MrHoldButton(r: Row, broker: String, wo: WoUi, onChanged: () -> Unit) {
    val deskSide = r.side?.uppercase()
    val fade = if (deskSide == "BUY") "SELL" else if (deskSide == "SELL") "BUY" else null
    val acct = wo.feed?.roster?.firstOrNull { it.name == broker }
    val why = when {
        wo.paired != true -> "🔐 pair the watch on the Trades page"
        broker == "Chart" -> "🏦 pick a broker on page 1 to fade"
        acct == null -> "🏦 $broker isn't on the account list"
        fade == null -> "no desk side to fade"
        wo.feed?.off == true -> "⛔ watch orders are OFF"
        else -> null
    }
    var res by remember(r.asset, broker) { mutableStateOf<String?>(null) }
    val label = if (why != null) why else "🔄 hold 5s → fade $fade ${r.asset}" + if (acct?.mode == "watch") " (ticket)" else ""
    HoldBar(5, label, Color(0xFFC084FC), enabled = why == null) {
        res = when (val x = Orders.signed("order", mapOf("asset" to r.asset, "side" to fade, "inst" to acct!!.inst, "broker" to acct.name, "mode" to "mr"))) {
            is WoResult.Ok -> describeOrder(x.json, acct.name)
            is WoResult.Err -> "❌ ${x.why}"
            is WoResult.Unpaired -> "🔐 ${x.why}"
        }
        onChanged()
    }
    res?.let { Text(it, color = C.muted, fontSize = 9.sp, textAlign = TextAlign.Center) }
}

// what the server answered, in this broker's own prices
private fun describeOrder(j: org.json.JSONObject, name: String): String {
    j.optJSONObject("ticket")?.let { t ->   // 👁 watch-only account: the hand ticket to type into MT5
        return "🎫 type into MT5: ${t.optString("side")} ${t.optString("symbol")} lot ${t.opt("lot")} · SL ${t.opt("sl")} · TP ${t.opt("tp")}"
    }
    val o = j.optJSONObject("order") ?: return "✅ sent to $name"
    val b = o.optJSONObject("preview")?.optJSONObject("broker")
    return if (b != null) "✅ ${o.optString("side")} ${o.optString("asset")} → $name · its prices: in ${fmtPrice(b.dbl("entry"))} · SL ${fmtPrice(b.dbl("sl"))} · TP3 ${fmtPrice(b.dbl("tp3"))}"
    else "✅ ${o.optString("side")} ${o.optString("asset")} → $name · its bot moves it into that broker's prices"
}

// 📆 _QT_WATCH_TRADES_24H_2026_10_23 — the day's closed trades (last 24 h), collapsed by default: a header with the W/L tally; tap to see each.
@Composable
private fun DoneSection(done: List<Done>) {
    if (done.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val wins = done.count { it.outcome == "win" }; val losses = done.count { it.outcome == "loss" }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel)
            .clickable { open = !open }.padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) "▾" else "▸", color = C.muted, fontSize = 11.sp, modifier = Modifier.width(13.dp))
        Text("📆 Today", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(" ${done.size} closed · 24h", color = C.muted, fontSize = 9.sp)
        Spacer(Modifier.weight(1f))
        Text("${wins}W ${losses}L", color = C.muted, fontFamily = MonoNum, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
    if (open) done.forEach { DoneCard(it) }
}

@Composable
private fun DoneCard(d: Done) {
    Spacer(Modifier.height(4.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${if (d.dir == "SELL") "▼" else "▲"} ${d.asset}", color = if (d.dir == "SELL") C.down else C.up, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            d.name?.let { Text(" · $it", color = C.accent, fontSize = 9.sp, maxLines = 1) }
            Spacer(Modifier.weight(1f))
            Text(outcomeBadge(d.outcome), color = outcomeColor(d.outcome), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
        Line("in→out", "${fmtPrice(d.entry)} → ${fmtPrice(d.exit)}", C.text)
        Line("R", d.r?.let { "%+.2f".format(it) } ?: "—", pnlColor(d.r))
        if (d.pnl != null) Line("P&L", signedMoney(d.pnl), pnlColor(d.pnl))
    }
}
private fun outcomeBadge(o: String?): String = when (o) { "win" -> "✅ win"; "loss" -> "❌ loss"; "be" -> "⟂ breakeven"; else -> o ?: "closed" }
private fun outcomeColor(o: String?): Color = when (o) { "win" -> C.up; "loss" -> C.down; else -> C.muted }

@Composable
private fun Line(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.muted, fontSize = 9.sp, fontFamily = MonoNum, textAlign = TextAlign.End, modifier = Modifier.width(44.dp), maxLines = 1)
        Text(" = ", color = C.muted, fontSize = 9.sp, fontFamily = MonoNum)
        Text(value, color = color, fontSize = 9.sp, fontFamily = MonoNum, maxLines = 1)
    }
}

private fun pnlColor(v: Double?): Color = when { v == null -> C.muted; v > 0 -> C.up; v < 0 -> C.down; else -> C.text }
private fun statusColor(s: String?): Color = when (s) { "placed", "closed" -> C.up; "refused", "expired" -> C.down; else -> C.gold }
private fun money(v: Double): String = "$" + "%,.0f".format(v)
private fun signedMoney(v: Double): String = (if (v >= 0) "+$" else "−$") + "%,.2f".format(abs(v))
private fun trimNum(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
