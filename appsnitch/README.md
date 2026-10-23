# AppSnitch: app risk audit UI (phone only)

> Part of [AppSnitch](../README.md). This folder is the phone UI; the scoring engine lives in [`../risk-auditor`](../risk-auditor).

A framework-free HTML/CSS/JS front-end (no build step, no npm needed to view it), designed for a phone screen
and meant to be wrapped as an Android APK. Open `www/index.html` in a browser (use the device toolbar at
360 to 412 px wide, or just a narrow window) to see it. It runs on sample data by default.
On a wide screen it still shows a single phone-width column (max 480 px); there is no desktop layout.

Layout: navy header (logo + privacy note), page title + search, one scrolling column of cards, and a fixed
bottom tab bar (Scan, Risk score, Prevention). Theme: navy `#0F1A26`, green `#3CBF84`, white workspace,
IBM Plex Sans / Outfit. The colours are CSS variables at the top of `css/styles.css`.

```
www/
  index.html            page shell (header, title + search, mounting point, bottom tab bar)
  css/styles.css        all styling, fonts are bundled locally (works offline in the APK)
  fonts/                IBM Plex Sans, IBM Plex Mono, Outfit
  js/
    config.js           WHERE DATA COMES FROM: mock | http | native
    app.js              navigation, scan lifecycle, click handling
    data/
      content.js        wording: rubric, categories, habits, settings shortcuts
      mock-data.js      sample scan, already in the real contract shape
      normalize.js      validates and cleans any response (never crashes the UI)
      providers.js      mock / native (Capacitor) / http implementations
      repository.js     the only API the UI uses for data
    ui/
      helpers.js        shared state, selectors, small helpers
      scan.js           page 1: Scan
      risk.js           page 2: Risk score (rubric + why flagged)
      prevent.js        page 3: Prevention
docs/
  DATA-CONTRACT.md      exact JSON the backend / Android plugin must return
  sample-scan.json      a full example response
tools/
  mock-server.js        dependency-free fake backend (node tools/mock-server.js), serves docs/sample-scan.json
tests/
  phone_test.py         127 browser checks at phone sizes (see "Tests" below)
```

## Who edits what (hackathon split)

| Person | Files |
|---|---|
| UI/UX | `css/`, `js/ui/`, `js/data/content.js`, `index.html` |
| Backend | returns JSON matching `docs/DATA-CONTRACT.md`, then set `config.js` |
| Android | the `AppScanner` Capacitor plugin (Kotlin), see below |

The UI never reads data directly. Everything goes through `AppSnitch.data` (`repository.js`), so
swapping sample data for a real source changes **one setting**, not the screens.

## Plug in a backend

1. Return JSON shaped like `docs/sample-scan.json` from an endpoint (default `GET /v1/scan`).
2. In `www/js/config.js`:
   ```js
   dataSource: 'http',
   api: { baseUrl: 'https://your-server.example', endpoints: { scan: '/v1/scan' } }
   ```
   Or test without editing code: `index.html?source=http&api=https://your-server.example`
3. Allow CORS for the app's origin. In a Capacitor app the origin is `https://localhost` (Android).
4. Need auth or a POST body (for example the raw app list)? Use `api.headers`, `api.method: 'POST'` and `api.getBody`.

While `http` is active, the privacy note in the header changes to say the app list is sent to the server (edit the wording in `config.js` under `privacy`).

Try it before the real backend exists: run `node tools/mock-server.js`, then open
`www/index.html?source=http&api=http://localhost:8787`. The mock also has failure switches for testing the
error screens (`endpoints.scan: '/v1/scan?mode=500|slow|empty|bad|text'`, listed at the top of the file).

What the UI does with a response (so the backend does not have to be perfect):
- Missing optional fields get defaults; bad apps and bad items are skipped and counted under the list (details in the browser console as `[AppSnitch] scan warnings`).
- `score` is clamped to 0-100 (or computed from `reasons[].points` if absent); `iconColor` must be `#rgb` or `#rrggbb`, anything else is replaced.
- All text is HTML-escaped, so app names or reasons containing markup are shown as plain text.
- A non-JSON answer, HTTP error, timeout (`api.timeoutMs`) or unreachable server shows a readable error card with **Try again**.

## Plug in real installed apps (Android)

1. Wrap `www/` with Capacitor: `npm i @capacitor/core @capacitor/cli @capacitor/android`, `npx cap init AppSnitch com.yourteam.appsnitch --web-dir=www`, `npx cap add android`.
2. Write a Capacitor plugin named `AppScanner` (Kotlin) with the methods in the last table of `DATA-CONTRACT.md`. It reads installed apps with `PackageManager`, scores them, and returns a ScanResult. Register it with `registerPlugin('AppScanner')`.
3. Set `dataSource: 'native'` in `config.js`.
4. After any UI change: `npx cap sync android`, then build in Android Studio.

Hybrid (common for hackathons): the plugin collects the raw app list, `http` + `api.getBody` posts it to the backend, and the backend returns the scored ScanResult.

### Phone / APK notes

- **Back button:** each tab change is a browser-history entry, so Android Back (Capacitor goes back in the WebView history) walks back through the tabs and closes the app from the first tab. Nothing to add.
- **Status bar / notch:** the page uses `viewport-fit=cover`, `env(safe-area-inset-*)` and `<meta name="theme-color">`. With the Capacitor StatusBar plugin use background `#0F1A26` and light icons (`Style.Dark`) so it blends into the navy header.
- **Keyboard:** keep `android:windowSoftInputMode="adjustResize"` (Capacitor's default). The tab bar hides while the keyboard is open and comes back when it closes.
- **Coming back from Settings:** after "Open settings" the app shows a hint to tap *Scan again*, because the scores on screen are stale. A native plugin should resolve `openAppSettings` as soon as it launches the screen.
- **Offline:** fonts and all assets are local; the only network call is the optional `http` provider.

Note: listing all installed apps needs the `QUERY_ALL_PACKAGES` permission on Android 11+. It is fine for sideloaded demo builds; Google Play requires a justification.

## Developer shortcuts

- `?fail=1` shows the scan error screen.
- Everything not per-app (rubric text, habits, categories, bands) is in `js/data/content.js`.
- Habit checkboxes are remembered on the device (localStorage).

## Tests

`tests/phone_test.py` drives a real Chromium at 320, 360, 390 and 412 px wide against the `mock`, `http` (using `tools/mock-server.js`) and `native` (a stubbed `AppScanner` plugin) sources. It checks: no sideways scrolling on any page or app, every flow (scan, filter, search, risk pages, fixes, uninstall confirm/undo, habits, Android back, keyboard), tap targets of 44 px or more, error states, hostile data (markup in names, bad JSON, duplicate ids, 300 apps) and that nothing leaves the app in mock mode.

```
pip install playwright && playwright install chromium   # once
python3 tests/phone_test.py                              # screenshots go to tests/out/
```

## Known gaps (planned next)

- App icons are colored letters. A real build should send an icon URL or base64 and the UI should show it.
- The rubric is displayed, not executed. Scoring lives in the backend or plugin.
