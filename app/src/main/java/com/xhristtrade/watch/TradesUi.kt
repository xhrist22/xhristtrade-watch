package com.xhristtrade.watch

import androidx.compose.foundation.background
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
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
                if (f == null || f.accounts.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("no open trades", color = C.muted, fontSize = 11.sp)
                }
                f?.accounts?.forEach { a -> AccountBlock(a, onChanged) }
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

@Composable
private fun AccountBlock(a: Account, onChanged: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    val watchOnly = a.mode == "watch"
    Text(a.name, color = C.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    Text("equity ${a.equity?.let { money(it) } ?: "?"} · open ${a.openPnl?.let { signedMoney(it) } ?: "?"}",
        color = pnlColor(a.openPnl), fontFamily = MonoNum, fontSize = 9.sp, maxLines = 1)
    a.order?.let { o -> Text("🖲 ${o.side ?: ""} ${o.asset ?: ""} · ${o.status ?: ""}${o.why?.let { " · $it" } ?: ""}",
        color = statusColor(o.status), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 3) }
    a.closing?.let { c -> Text("✖ ${c.asset ?: ""} · ${c.status ?: ""}${c.why?.let { " · $it" } ?: ""}",
        color = statusColor(c.status), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 3) }
    a.trades.forEach { t ->
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (t.dir == "SELL") "▼" else "▲"} ${t.asset}", color = if (t.dir == "SELL") C.down else C.up,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                HoldBar(3, "✖ hold 3s → close ${t.asset}", C.down) {
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
