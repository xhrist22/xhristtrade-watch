# ⌚ XhristTrade — Galaxy Watch (Wear OS) app

A glance-and-swipe read of your desk on your wrist. One card per asset
(**BTC · ETH · NAS100 · SPX500 · GOLD · SILVER** — your ChartWire order), each showing:

- **30-min sparkline** (last 30 one-minute closes)
- **KURU** — Lead / 2× / 4× pressure histogram (coiling vs firing, bull/bear)
- **WAE** — up/down momentum + explosion & dead-zone lines, with the desk's mode word
- **LRC** channel gauge — price in the channel, EMA9 tick, tinted by candle temperature
- **Plan** — 🟩/🟧 SL (+pays, ×ATR) · ⚖️ RR · 📈 TP3 (+reach% · source) · 🟧 2nd SL
- the reason it isn't a trade yet

Swipe left/right between assets; the last page is a hub with **Open QT on phone**.

**Data-only. No AI, no voice, no account data.** It reads exactly one endpoint:
`GET <BASE_URL>/api/qe/watch?k=<WATCH_KEY>` over HTTPS. That code gets the **viewer**
role, so the server scrubs all accounts and 403s every AI/owner endpoint — the watch
can only ever see this read-only feed. The owner code is never on the watch.

Theme matches Quant Terminal / ChartWire exactly (TradingView dark palette).

---

## The one secret: the watch code

The watch authenticates with a dedicated viewer code stored on the server at
`/root/qt/watch_key.txt` (nginx tags only that code `X-QT-Watch:1`). Read it with:

```
ssh vps 'cat /root/qt/watch_key.txt'
```

Put that value in `WATCH_KEY` (local build) or the `QT_WATCH_KEY` GitHub secret (CI).
It never belongs in git — `local.properties` is gitignored.

---

## Option A — build in the cloud, get it on Telegram (recommended)

1. In GitHub → this repo → **Settings → Secrets and variables → Actions**, add:
   - `QT_WATCH_KEY` — from the command above  *(required)*
   - `TG_BOT_TOKEN` — @XhristTradeBot's token  *(required to DM)*
   - `TG_CHAT_ID`  — your Telegram DM id, `2062723714`  *(required to DM)*
   - `QT_BASE_URL` / `QT_PHONE_URL` — optional (defaults are xhristtrade.com / terminal.xhristtrade.com)
2. **Actions → "Wear APK → Telegram" → Run workflow** (or push any change under `galaxy-watch/`).
3. @XhristTradeBot DMs you `app-debug.apk`. (It's also attached to the run as an artifact.)
4. On your phone, open **Wear Installer 2** (free, Play Store), pick the APK from your
   Telegram download, and push it to the paired Galaxy Watch over Wi-Fi. Done — the
   **XhristTrade** app is now on the watch.

> A Wear OS watch can't tap-install a raw APK like a phone, which is why Wear Installer 2
> (phone → watch over ADB-WiFi) is the no-computer path.

## Option B — build on your Mac (Android Studio)

1. Open `galaxy-watch/` in Android Studio (it generates the Gradle wrapper on first sync).
2. Copy `local.properties.example` → `local.properties`, set `WATCH_KEY` (and `sdk.dir`).
3. **Build → Build APK**, or `./gradlew :app:assembleDebug`.
4. Install to the watch over Wi-Fi ADB:
   ```
   adb connect <watch-ip>:5555     # enable ADB debugging + Wi-Fi debugging on the watch first
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Config keys

| key | default | what |
|-----|---------|------|
| `BASE_URL`  | `https://xhristtrade.com` | the viewer feed host |
| `WATCH_KEY` | *(empty)* | the dedicated watch viewer code — **required** |
| `PHONE_URL` | `https://terminal.xhristtrade.com` | what "Open QT on phone" opens |

CI reads these from env/secrets first, then `local.properties`, then the defaults.

## Build facts
Kotlin · Jetpack Compose for Wear OS · minSdk 30 (Galaxy Watch 4+) · targetSdk 34 ·
AGP 8.5.2 · Gradle 8.7. Polls the feed every 12 s. No third-party network libs
(HttpURLConnection + org.json).
