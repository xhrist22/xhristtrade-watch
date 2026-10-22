package com.xhristtrade.watch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ⌚🔐 _QT_WATCH_ORDERS_SIGNED_2026_10_22 — the watch's trade calls. Every one (but asking to pair) is a payload signed by Signer's hardware
// key: { act, dev, ts, nonce, …fields } → POST /api/qe/wo/<act> { payload, sig }. The server honours it only from a key Sam approved on QT,
// inside 30 s of `ts`, once per nonce. Orders and closes then run through QT's own order path (the account's bot places it in ITS broker's
// prices and DMs the result).

data class Trade(
    val asset: String, val dir: String?, val fill: Double?, val lot: Double?, val now: Double?, val pnl: Double?, val r: Double?,
    val sl: Double?, val tp: Double?, val lockedR: Double?, val positionId: String?, val trailSl: Double?,
)
data class Pending(val asset: String?, val side: String?, val status: String?, val why: String?)
data class Account(
    val inst: String, val name: String, val mode: String?, val equity: Double?, val openPnl: Double?,
    val order: Pending?, val closing: Pending?, val trades: List<Trade>,
)
data class RosterAcct(val inst: String, val name: String, val mode: String?)
data class TradesFeed(val accounts: List<Account>, val roster: List<RosterAcct>, val off: Boolean)

sealed class WoResult {
    data class Ok(val json: JSONObject) : WoResult()
    data class Unpaired(val why: String) : WoResult()
    data class Err(val why: String) : WoResult()
}

object Orders {
    private fun base(act: String) = BuildConfig.BASE_URL.trimEnd('/') + "/api/qe/wo/" + act + "?k=" + BuildConfig.WATCH_KEY

    private fun post(act: String, body: JSONObject): Pair<Int, JSONObject?> {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(base(act)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 15000
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-QT", "1")
                setRequestProperty("User-Agent", "XhristTradeWatch/1.0 (WearOS)")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to (try { JSONObject(txt) } catch (e: Exception) { null })
        } finally {
            conn?.disconnect()
        }
    }

    /** Ask the server to trust this watch. The code to show comes from OUR key (Signer.pairCode), never from the server. */
    suspend fun pair(): WoResult = withContext(Dispatchers.IO) {
        if (BuildConfig.WATCH_KEY.isBlank()) return@withContext WoResult.Err("no watch code in this build")
        try {
            val (code, j) = post("pair", JSONObject().put("pub", Signer.publicB64()))
            if (code == 200 && j != null) WoResult.Ok(j) else WoResult.Err(j?.strn("why") ?: "server said $code")
        } catch (e: Exception) { WoResult.Err(e.message?.take(60) ?: "can't reach the server") }
    }

    /** One signed call. `fields` are the action's own facts (asset, side, inst, broker, position_id). */
    suspend fun signed(act: String, fields: Map<String, Any?> = emptyMap()): WoResult = withContext(Dispatchers.IO) {
        if (BuildConfig.WATCH_KEY.isBlank()) return@withContext WoResult.Err("no watch code in this build")
        try {
            val msg = JSONObject().put("act", act).put("dev", Signer.deviceId()).put("ts", System.currentTimeMillis()).put("nonce", Signer.nonce())
            fields.forEach { (k, v) -> msg.put(k, v ?: JSONObject.NULL) }
            val payload = msg.toString()
            val (code, j) = post(act, JSONObject().put("payload", payload).put("sig", Signer.sign(payload)))
            when {
                j != null && j.optBoolean("unpaired", false) -> WoResult.Unpaired(j.strn("why") ?: "not paired")
                code in 200..299 && j != null -> WoResult.Ok(j)
                else -> WoResult.Err(j?.strn("why") ?: "server said $code")
            }
        } catch (e: Exception) { WoResult.Err(e.message?.take(60) ?: "can't reach the server") }
    }
}

fun parseTrades(j: JSONObject): TradesFeed {
    fun pend(o: JSONObject?) = o?.let { Pending(it.strn("asset"), it.strn("side"), it.strn("status"), it.strn("why")) }
    val accts = mutableListOf<Account>()
    val a = j.optJSONArray("accounts")
    for (i in 0 until (a?.length() ?: 0)) {
        val o = a!!.getJSONObject(i)
        val tr = mutableListOf<Trade>()
        val ta = o.optJSONArray("trades")
        for (k in 0 until (ta?.length() ?: 0)) {
            val t = ta!!.getJSONObject(k)
            tr += Trade(t.optString("asset"), t.strn("dir"), t.dbl("fill"), t.dbl("lot"), t.dbl("now"), t.dbl("pnl"), t.dbl("r"),
                t.dbl("sl"), t.dbl("tp"), t.dbl("locked_r"), t.strn("position_id"), t.dbl("trail_sl"))
        }
        accts += Account(o.optString("inst"), o.optString("name"), o.strn("mode"), o.dbl("equity"), o.dbl("open_pnl"),
            pend(o.objn("order")), pend(o.objn("closing")), tr)
    }
    val roster = mutableListOf<RosterAcct>()
    val ra = j.optJSONArray("roster")
    for (i in 0 until (ra?.length() ?: 0)) {
        val o = ra!!.getJSONObject(i)
        roster += RosterAcct(o.optString("inst"), o.optString("name"), o.strn("mode"))
    }
    return TradesFeed(accts, roster, j.optBoolean("off", false))
}
