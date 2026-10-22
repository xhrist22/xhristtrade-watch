package com.xhristtrade.watch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class FetchResult {
    data class Ok(val feed: Feed) : FetchResult()
    data class Err(val code: Int, val message: String) : FetchResult()
}

object Net {
    // One call, one credential: GET <BASE_URL>/api/qe/watch?k=<WATCH_KEY> over HTTPS.
    // No cookies, no session — the watch code is the whole credential (nginx tags it X-QT-Watch:1).
    suspend fun fetchWatch(): FetchResult = withContext(Dispatchers.IO) {
        val key = BuildConfig.WATCH_KEY
        if (key.isBlank()) {
            return@withContext FetchResult.Err(0, "No watch code in this build.\nSet WATCH_KEY and rebuild.")
        }
        val spec = BuildConfig.BASE_URL.trimEnd('/') + "/api/qe/watch?k=" + key
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(spec).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 20000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "XhristTradeWatch/1.0 (WearOS)")
            }
            val code = conn.responseCode
            if (code != 200) {
                val msg = when (code) {
                    401, 403 -> "Access denied — the watch code isn't accepted. Check WATCH_KEY."
                    429 -> "Easy — too many requests. Retrying."
                    in 500..599 -> "Server busy ($code). Retrying."
                    else -> "Server said $code."
                }
                return@withContext FetchResult.Err(code, msg)
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            FetchResult.Ok(parseFeed(body))
        } catch (e: Exception) {
            FetchResult.Err(-1, e.message?.take(80) ?: "Can't reach the server.")
        } finally {
            conn?.disconnect()
        }
    }

    // 📱 steer the OPEN phone QT page to this asset, in place (no reload). Needs the X-QT:1 write header.
    suspend fun postFocus(symbol: String): Boolean = withContext(Dispatchers.IO) {
        val key = BuildConfig.WATCH_KEY
        if (key.isBlank()) return@withContext false
        val spec = BuildConfig.BASE_URL.trimEnd('/') + "/api/qe/focus?k=" + key
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(spec).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 12000
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-QT", "1")   // the server's CSRF write header
                setRequestProperty("User-Agent", "XhristTradeWatch/1.0 (WearOS)")
            }
            val safe = symbol.replace("\\", "").replace("\"", "")
            conn.outputStream.use { it.write("{\"symbol\":\"$safe\"}".toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
