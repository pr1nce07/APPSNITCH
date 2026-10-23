# AppSnitch data contract (schemaVersion 1)

The UI shows whatever a provider returns, as long as it matches this shape.
A complete working example is in `docs/sample-scan.json`.

The backend (or the Android plugin) decides **the scores**. The UI only displays them.
Scoring rules are shown in the app from `www/js/data/content.js` (`rubric`); keep them in sync.

## ScanResult

```jsonc
{
  "schemaVersion": 1,                    // required, must be 1
  "scannedAt": "2026-10-16T09:00:00Z",   // optional, ISO 8601
  "device": { "model": "Pixel 8", "androidVersion": "14" },   // optional
  "apps": [ /* App, see below */ ]
}
```

## App

| Field | Type | Required | Notes |
|---|---|---|---|
| `packageName` | string | **yes** | e.g. `com.brightapps.torchlite`. Apps without it are skipped. |
| `id` | string | no | Unique per scan. Defaults to `packageName`. Duplicates are skipped. |
| `name` | string | no | Display name. Defaults to `packageName`. |
| `versionName` | string | no | Shown as `v2.3.1`. |
| `installedAt` | string or null | no | ISO date. `null` for built-in apps. |
| `installSource` | `"play_store"` \| `"sideloaded"` \| `"system"` \| `"other"` | no | Default `other`. Drives the "Outside Play Store" warnings. |
| `isSystem` | boolean | no | Hidden unless "Show system apps" is ticked. |
| `targetsOldAndroid` | boolean | no | Shows the old-Android tag. |
| `oldAndroidLabel` | string | no | Text of that tag, e.g. `Built for Android 5 (2014)`. |
| `iconColor` | string (hex) | no | `#rgb` or `#rrggbb` only (anything else is replaced). Default: derived from `packageName`. |
| `iconLetter` | string | no | Default: first letter of `name`. (Real icons are a planned extension.) |
| `score` | number 0-100 | no | Clamped to 0-100. If missing, the sum of `reasons[].points` (clamped) is used. |
| `reasons` | Reason[] | no | Why the app was flagged. Shown sorted by points. |
| `access` | object | no | Permissions grouped by category, see below. |
| `fixes` | Fix[] | no | Steps shown on the Prevention page. Apps with score under 25 or no fixes are listed as "no action". |

The risk **band** is derived from `score` using `content.js` (`bands`): 0-29 Low, 30-59 Medium, 60-79 High, 80-100 Critical (same thresholds as risk-auditor rubric.json).

### Reason

```json
{ "kind": "access", "category": "texts", "latent": false,
  "title": "Can read your text messages",
  "detail": "It can see every message you get, including one-time codes.",
  "points": 15 }
```

- `kind`: `access` | `context` | `combo` | `trust`. Groups the "How 94 adds up" summary.
- `category`: only for `access`; one of the access category keys below.
- `latent`: `true` if the permission is declared but not granted yet.
- `points`: integer, may be negative (trust bonuses).

### Access

Keys are the categories: `special`, `texts`, `contacts`, `camera`, `location`, `phone`, `files`, `background`, `network`. Unknown keys are ignored with a console warning.

```json
"access": {
  "texts": [
    { "label": "Read your texts", "permission": "READ_SMS", "granted": true, "protection": "dangerous" }
  ],
  "special": [
    { "label": "Read the screen and tap for you", "permission": "BIND_ACCESSIBILITY_SERVICE",
      "granted": true, "protection": "special", "screenReader": true }
  ]
}
```

`protection`: `dangerous` | `normal` | `special`. `screenReader: true` marks the accessibility-service item (counted in the "can read your screen" tile).

### Fix

```json
{ "text": "Uninstall it. A flashlight has no reason to read your texts.", "action": "uninstall" }
```

`action`: `uninstall` (shows an Uninstall button), `settings` (shows Open settings), `info` (text only).

## Forgiving by design

The UI never crashes on a bad response. Missing optional fields get defaults, unknown values are replaced with safe ones, and bad apps are skipped. The number of fixed or skipped items shows under the table and the details go to the browser console (`[AppSnitch] scan warnings`).
A response that is not `{ "apps": [...] }` shows a readable error screen with a **Try again** button.

## Device actions (native plugin)

When the UI runs inside the Android app, these Capacitor plugin methods are used (plugin name in `config.js`, default `AppScanner`):

| Method | Input | Should |
|---|---|---|
| `scan()` | none | Resolve with a ScanResult. May emit `scanProgress` events `{done,total,current}`. |
| `uninstall({packageName})` | | Open the system uninstall prompt. Resolve only if the app is gone; reject if the user cancelled. |
| `openAppSettings({packageName})` | | Open that app's system settings page. |
| `openSystemSetting({setting})` | `accessibility`, `overlay`, `notification_access`, `install_unknown_apps` | Open that settings screen. |
