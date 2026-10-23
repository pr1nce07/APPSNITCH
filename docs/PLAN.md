# On-Device App Risk Auditor — Requirements & Build Logic

**Problem Statement:** PS-01 · Mobile Security · Medium · 6 hours
**Required output:** Working APK (installed on the judging device by end of hour 5)

---

## 1. What the problem actually asks for

Strip away the formatting and the ask is one pipeline:

> Enumerate every installed app → read what each one is *allowed* to do → bucket those abilities into risk categories → turn that into a number plus a sentence a normal person understands → let them act on it.

The three user questions in the PS are your acceptance test. Every screen you build must answer one of them:

| Question | What it maps to in the app |
|---|---|
| **Q1** Which of my apps can do the most harm? | Home list, sorted by risk score descending, colour-banded |
| **Q2** Why is each one flagged? | "Why flagged" panel — plain-language reasons, no permission constants shown raw |
| **Q3** What can I do about it right now? | Two buttons: open system settings page, uninstall |

If a judge can open your APK and answer all three in under 60 seconds without you narrating, you pass the core demo.

### 1.1 Deliverables (all four are mandatory)

1. **Working APK** — installed and running by end of hour 5
2. **Scoring rubric** — one page, risk weights + combination rules (write this *while* you code the engine, not after)
3. **Source code** — repo link
4. **Live demo** — 3 minutes, run on the **organizer test kit**, not apps you picked

> ⚠️ **The single most important constraint:** judging uses the organizer test kit on *their* device. You cannot hardcode, allowlist, or tune for specific packages. Your rules must be generic and must survive apps you have never seen.

### 1.2 Hard constraints

| Constraint | Design consequence |
|---|---|
| On-device only, no root, no ADB | Everything via `PackageManager`, `AppOpsManager`, `Settings.Secure`. No shell. |
| No user data leaves device | No analytics SDK, no crash reporter, no network calls — except optional hash lookup |
| Offline first | Scan, score, explain, and render must all work in airplane mode |
| `QUERY_ALL_PACKAGES` allowed | Declare it. Without it, Android 11+ shows you almost nothing. |
| No live malware | Demo on the test kit's benign-but-over-permissioned apps |

---

## 2. Architecture — five layers, one direction

Keep the data flowing one way. This is what makes the scoring engine testable and the UI dumb.

```
┌──────────────────────────────────────────────────┐
│ 5. UI          Home list · Detail · Summary      │
├──────────────────────────────────────────────────┤
│ 4. Explain     Reason objects → plain sentences  │
├──────────────────────────────────────────────────┤
│ 3. Score       Base weights + combo rules → 0-100│
├──────────────────────────────────────────────────┤
│ 2. Categorize  Permission → one of 9 categories  │
├──────────────────────────────────────────────────┤
│ 1. Collect     Inventory · Permissions · AppOps  │
└──────────────────────────────────────────────────┘
```

**Rule:** layers 1–4 are pure Kotlin with no Android UI imports. Layer 3 takes an `AppProfile` and returns a `RiskResult`. That means you can unit-test scoring with fake data while someone else builds screens — critical when you have 6 hours and a team.

---

## 3. Data model (define this first — hour 0)

Write these classes before anything else. They are the contract between everyone on the team.

```kotlin
data class AppProfile(
    val packageName: String,
    val label: String,
    val versionName: String,
    val firstInstallTime: Long,
    val installerPackage: String?,      // null = sideloaded
    val targetSdk: Int,
    val isSystemApp: Boolean,
    val apkPath: String,                // for SHA-256 stretch goal
    val declaredCategory: Int,          // ApplicationInfo.category
    val permissions: List<PermissionState>,
    val specialAccess: Set<SpecialAccess>
)

data class PermissionState(
    val name: String,                   // android.permission.READ_SMS
    val granted: Boolean,
    val protection: Protection,         // NORMAL | DANGEROUS | SIGNATURE | SPECIAL
    val category: RiskCategory
)

enum class SpecialAccess {
    ACCESSIBILITY, OVERLAY, DEVICE_ADMIN,
    NOTIFICATION_LISTENER, INSTALL_PACKAGES, USAGE_STATS
}

data class Reason(
    val headline: String,               // "Can read your screen"
    val detail: String,                 // one plain sentence
    val points: Int,
    val severity: Severity
)

data class RiskResult(
    val score: Int,                     // 0..100
    val band: Band,                     // LOW | MEDIUM | HIGH | CRITICAL
    val reasons: List<Reason>,          // sorted by points desc
    val categoryBreakdown: Map<RiskCategory, Int>
)
```

---

## 4. The logic, in build order

### Phase 1 — Inventory collection

**Goal:** a list of `AppProfile` with metadata filled, permissions empty.

```kotlin
val flags = PackageManager.GET_PERMISSIONS
val packages = pm.getInstalledPackages(flags)
```

Field-by-field sourcing:

| Field | API |
|---|---|
| Name / icon | `applicationInfo.loadLabel(pm)` / `loadIcon(pm)` |
| Version | `packageInfo.versionName` |
| Install date | `packageInfo.firstInstallTime`, `lastUpdateTime` |
| Installer source | API 30+: `pm.getInstallSourceInfo(pkg).installingPackageName`; below: `pm.getInstallerPackageName(pkg)` |
| Target SDK | `applicationInfo.targetSdkVersion` |
| System app | `flags and ApplicationInfo.FLAG_SYSTEM != 0` **or** `FLAG_UPDATED_SYSTEM_APP != 0` |

**System app toggle:** filter on the flag above. Default the toggle **off** — the PS asks for user-installed apps, and leaving system apps in will flood your list with scary-looking but unavoidable OEM packages.

**Performance note:** loading icons for 150+ apps on the main thread will freeze the UI and lose you demo points. Load metadata + permissions on a background dispatcher; load icons lazily per visible row.

---

### Phase 2 — Permission extraction (declared vs granted)

This distinction is explicitly required and is also where most teams get sloppy.

```kotlin
val declared = packageInfo.requestedPermissions ?: emptyArray()
val flagsArr = packageInfo.requestedPermissionsFlags

declared.forEachIndexed { i, perm ->
    val granted = flagsArr[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
    ...
}
```

**Protection level** — `pm.getPermissionInfo(name, 0)`:
- API 28+: `.protection` (NORMAL / DANGEROUS / SIGNATURE) and `.protectionFlags`
- Below: `.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE`
- Wrap in try/catch — `NameNotFoundException` is thrown for custom or vendor permissions. Treat unknown as NORMAL and move on.

**Why it matters for scoring:** a *declared but not granted* dangerous permission is latent risk, not active risk. Score it at reduced weight (see §5) and say so in the reason text. This nuance reads as sophistication to a judge.

---

### Phase 3 — Special access detection (highest weight — get this right)

This is the section that separates a demo from a toy. Each of the six needs a different API.

| Access | Detection |
|---|---|
| **Accessibility** | `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` → map `resolveInfo.serviceInfo.packageName`. Or parse `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. |
| **Notification listener** | `NotificationManagerCompat.getEnabledListenerPackages(context)` (or `Settings.Secure.getString(cr, "enabled_notification_listeners")`) |
| **Device admin** | `DevicePolicyManager.activeAdmins` → `componentName.packageName` |
| **Overlay** | Declared: `SYSTEM_ALERT_WINDOW` in `requestedPermissions`. Granted (best effort): `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)` |
| **Install packages** | Declared: `REQUEST_INSTALL_PACKAGES`. Granted: AppOps `OPSTR_REQUEST_INSTALL_PACKAGES` |
| **Usage stats** | Declared: `PACKAGE_USAGE_STATS`. Granted: AppOps `OPSTR_GET_USAGE_STATS` |

**AppOps caveat — plan for it:** `unsafeCheckOpNoThrow` for *other* packages behaves inconsistently across OEM builds and Android versions; it may return `MODE_DEFAULT` or throw. Build it as:

```
granted state = AppOps result if usable, else fall back to "declared"
```

and surface the difference honestly in the UI ("declares overlay access" vs "has overlay access"). Do **not** let an AppOps exception crash the scan — wrap every call and default to the declared state. A scan that completes with slightly softer language beats a scan that dies on the judging device.

---

### Phase 4 — Categorization

A single immutable map from permission string → `RiskCategory`. All nine categories from §2 of the PS must be covered.

```kotlin
val PERMISSION_CATEGORY = mapOf(
    "android.permission.READ_SMS" to SMS,
    "android.permission.RECEIVE_SMS" to SMS,
    "android.permission.SEND_SMS" to SMS,
    "android.permission.READ_CONTACTS" to CONTACTS_CALLS,
    "android.permission.READ_CALL_LOG" to CONTACTS_CALLS,
    "android.permission.CAMERA" to CAMERA_MIC,
    "android.permission.RECORD_AUDIO" to CAMERA_MIC,
    // ... location, phone identity, storage, background, network
)
```

Anything not in the map → `OTHER`, weight 0. Don't try to be exhaustive; cover the PS examples plus the obvious siblings (`ACCESS_COARSE_LOCATION`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_VIDEO`, `ANSWER_PHONE_CALLS`, etc.).

**Two outputs required:**
1. **Per app** — categories this app touches, with which permissions in each
2. **Device-wide summary** — "14 apps can read your SMS · 3 apps can record audio · 2 apps can read your screen". This is your home-screen hero section and it is explicitly required.

---

### Phase 5 — Risk scoring engine

Three stages: base score → combination bonuses → trust modifiers → clamp → band.

#### 5.1 Base weights (per granted item)

| Category | Weight | Notes |
|---|---:|---|
| Special access | 25 each | Accessibility, device admin, notification listener highest |
| SMS | 15 | Any one SMS permission; cap the category at 20 |
| Contacts & call logs | 10 | |
| Camera & microphone | 10 | |
| Location | 8 | `ACCESS_BACKGROUND_LOCATION` adds +5 |
| Phone & identity | 6 | |
| Storage & media | 6 | `MANAGE_EXTERNAL_STORAGE` is 12 — it's all-files access |
| Background & persistence | 4 | |
| Network | 2 | Low alone, amplifier in combos |

**Declared-but-not-granted:** count at **25% weight**. Latent, not active.

**Diminishing returns within a category:** first permission full weight, each additional in the same category at 50%. Otherwise an app with five storage permissions out-scores an app with accessibility service, which is wrong.

#### 5.2 Combination rules (the "should have" that wins points)

Flat bonuses, applied after base:

| Rule | Bonus | Why |
|---|---:|---|
| Accessibility **+** overlay | +25 | Classic banking-trojan pair: read the screen, draw a fake login over it |
| SMS read **+** internet **+** boot persistence **+** sideloaded | +20 | OTP interception and exfiltration |
| Device admin **+** not a security/MDM app | +15 | Uninstall blocking |
| Notification listener **+** internet | +15 | OTP capture from notification shade |
| Accessibility **+** SMS | +15 | Screen reading plus message access |
| Overlay **+** install packages | +10 | Fake prompt leading to a dropper install |
| Any special access **+** sideloaded install source | +10 | |

Detecting "is a security/MDM app": check `ApplicationInfo.category` and installer. Be honest in the reason text — say "unless this is your company's device management app", which is both accurate and demo-friendly.

#### 5.3 Context mismatch (also a "should have")

Flag an app whose *stated purpose* doesn't fit its *access*. Offline, you have two signals:

1. `ApplicationInfo.category` (API 26+) — e.g. `CATEGORY_GAME` holding SMS access
2. Label keyword buckets — `torch|flashlight|calculator|wallpaper|clock|notepad|qr|scanner`

Rule: if the app falls in a "simple utility" bucket **and** holds any permission from SMS / Contacts / Camera-Mic / Special access → **+20 and force minimum band HIGH**. The PS calls this out by name; a torch app with SMS access is the single most legible demo moment you have.

#### 5.4 Trust modifiers

| Signal | Effect |
|---|---|
| Installer = `com.android.vending` | −10 (floor at 0) |
| Installer null/unknown (sideloaded) | +10 |
| `targetSdk < 23` | +10 — predates runtime permissions, gets everything at install |
| System app | −15, and never rank above a user app at the same raw score |

#### 5.5 Bands

```
0–24   LOW       green
25–49  MEDIUM    amber
50–74  HIGH      orange
75–100 CRITICAL  red
```

Clamp to 0..100 at the end. **Never** show a raw number above 100 or a negative.

---

### Phase 6 — Explanation generation

Every scoring rule that fires must emit a `Reason` object. Never generate explanation text separately from scoring — if they drift apart, the score says 82 and the reasons list two mild items, and a judge will notice.

```kotlin
// inside the engine, not after it
if (SpecialAccess.ACCESSIBILITY in profile.specialAccess) {
    score += 25
    reasons += Reason(
        headline = "Can read everything on your screen",
        detail = "This app uses an accessibility service. That lets it see the " +
                 "text of any app you open, including banking apps, and tap buttons for you.",
        points = 25,
        severity = Severity.CRITICAL
    )
}
```

**Requirement:** at least **three** plain-language reasons for every High or Critical app. If the rules produced fewer than three, pad from the highest-weighted base categories ("Can read your text messages", "Can access your contacts"). Sort by points descending so the worst reason is first.

**Writing style for `detail`:** no permission constants, no Android jargon. Say "can read your text messages", not "holds READ_SMS (dangerous)". Keep the constant visible in a collapsible technical section for the judges who want it.

---

### Phase 7 — UI (three screens, that's all)

**Screen 1 — Home / device summary**
- Header: device risk at a glance — counts per band, top category stats
- Toggle: include system apps
- Sorted list: icon · name · score chip · band colour · one-line top reason
- Sort/filter: by score (default), by category

**Screen 2 — App detail**
- Header: icon, name, package, version, install date, installer source, target SDK
- Score + band, prominent
- **"Why flagged"** panel — the reason list
- Category breakdown — nine categories, which permissions in each, granted vs declared
- Technical section (collapsed): raw permission list with protection levels
- Two action buttons (below)

**Screen 3 — Scan progress**
- A progress indicator with a count. A 150-app scan takes a few seconds; silence looks like a freeze.

---

### Phase 8 — Actions (Q3 — do not skip)

```kotlin
// Open the app's system settings page
Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
       Uri.fromParts("package", pkg, null))

// Uninstall
Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
```

Add deep links for special access, since that's where the real risk is and the user can't find these screens:
- `Settings.ACTION_ACCESSIBILITY_SETTINGS`
- `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
- `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`
- `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`

Hide or disable uninstall for system apps — the intent will just fail and look broken on stage.

---

### Phase 9 — Should-haves (only after must-haves are green)

**Install monitoring (#9).** `BroadcastReceiver` for `ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REPLACED` (`android:scheme="package"`). Manifest-registered receivers for these are unreliable across versions and OEM power management — register at runtime and back it with a periodic `WorkManager` rescan so the feature still demonstrates. On High/Critical, post a notification.

**Permission diff (#10).** Persist a snapshot after each scan — Room table or a JSON file of `package → sorted permission set + specialAccess set`. On rescan, diff:
```
newlyGranted = current − previous
```
If any new item is in SMS / Special access / Camera-Mic → alert. This is cheap to build once snapshots exist, and it's a strong demo beat.

---

### Phase 10 — Stretch (only if you're genuinely ahead)

| # | Item | Implementation sketch |
|---|---|---|
| 11 | SHA-256 lookup | Stream `applicationInfo.sourceDir` through `MessageDigest`. **Consent dialog before any network call** — the privacy constraint is explicit. Hashes only, never the APK. |
| 12 | Static manifest checks | `GET_ACTIVITIES or GET_SERVICES or GET_RECEIVERS or GET_PROVIDERS` → count `exported` components. `FLAG_USES_CLEARTEXT_TRAFFIC` and `FLAG_DEBUGGABLE` from `applicationInfo.flags`. All offline, all cheap — this is the best value stretch goal. |
| 13 | On-device ML | Must be trained **before** the event. The PS is explicit: the rule score stays the source of explanations. Show the ML score beside it, never instead of it. |
| 14 | PDF report | `PdfDocument` or `PrintManager`. Nice-to-have; skip if time is tight. |

---

## 5. Six-hour schedule

| Hour | Target | Done when |
|---|---|---|
| **0–0.5** | Project setup, manifest permissions, data classes | Classes compile, team agrees on the contract |
| **0.5–1.5** | Phase 1 + 2: inventory + permissions | Logcat prints every app with granted/declared counts |
| **1.5–2.5** | Phase 3: special access | Accessibility/listener/admin detection verified against a real enabled service |
| **2.5–3.5** | Phase 4 + 5: categories + scoring engine | Engine returns a score for every app; rubric page drafted in parallel |
| **3.5–4.5** | Phase 6 + 7: reasons + UI | List and detail render on device |
| **4.5–5.0** | Phase 8: actions, polish, **build APK** | **APK installed on judging device — hard deadline** |
| **5.0–5.5** | Should-haves: combos tuned, context mismatch, diff | |
| **5.5–6.0** | Demo rehearsal on the organizer test kit | 3 minutes, timed, twice |

**Non-negotiable:** a signed, installed APK exists at hour 5 even if it only does must-haves 1–6. Ship that, then add. Do not be mid-refactor at hour 5.

---

## 6. Failure modes to design against

1. **Crash on the judging device.** Different OEM, different Android version. Wrap every `PackageManager` and `AppOpsManager` call in try/catch and skip the app rather than aborting the scan. One bad package must not kill the list.
2. **Tuned to your own phone.** You will not see the test kit until demo time. Never hardcode package names. Test by sideloading a few random APKs and checking the output still reads sensibly.
3. **Score and reasons disagree.** Emit reasons from inside the scoring rules, never as a separate pass.
4. **Everything scores Critical.** If 40 apps are red, the ranking is useless and Q1 is unanswered. Sanity check: a mainstream messaging app should land Medium–High, a torch app with SMS access should be the only Critical on screen. Tune weights against that target, not against a formula you like.
5. **Frozen UI on scan.** Background dispatcher, lazy icons, visible progress.
6. **Android 11+ visibility.** `QUERY_ALL_PACKAGES` declared in the manifest — without it your list is nearly empty and you'll lose 20 minutes to confusion.
7. **Rubric written last.** It's a graded deliverable. Fill in the weights table as you code the engine; it's then just a copy-paste at hour 5.

---

## 7. Demo script (3 minutes, on their kit)

1. **0:00–0:20** — Open app, scan runs, device summary appears. "47 apps scanned on-device, nothing left the phone."
2. **0:20–1:00** — Home list, sorted. Point at the top entry. *Q1 answered.*
3. **1:00–2:00** — Tap the worst app. Read two reasons aloud in plain English. Show the category breakdown and the granted-vs-declared distinction. *Q2 answered.*
4. **2:00–2:30** — Tap through to system settings, show the uninstall button. *Q3 answered.*
5. **2:30–3:00** — One differentiator: context mismatch (a utility app holding SMS access) or the accessibility+overlay combination rule, with the sentence explaining why that pair is how banking trojans work.

Rehearse on a device you have not pre-scanned. The judges' kit will contain apps you've never seen, and that's the point.
