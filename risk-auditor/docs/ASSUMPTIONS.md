# Assumptions

Decisions taken without asking, as instructed. Each records what was assumed,
why, and what to change if the assumption is wrong.

---

## A-01 · Band thresholds: 0-29 / 30-59 / 60-79 / 80-100

The starting values from the brief, kept after calibration against the twelve
profiles in `tools/offline-verify/src/Calibration.kt`. The calibration table is
reproduced in `docs/PROGRESS.md`; the decisive checks are that a legitimate
messenger lands Medium and a torch app holding SMS lands Critical.

**Change if:** the judging kit produces a flood of Criticals. Raise
`critical_from`, not the category weights — the weights are tuned relative to
each other.

---

## A-02 · The permission subtotal is capped at 45 points

The single most consequential number in the rubric, and not in the brief.

Without a cap, scoring is additive and any feature-rich app — a messenger, a
camera app, a browser — outscores a torch app holding one accessibility service.
Every app turns red, the ranking becomes meaningless, and PS-01's Q1 ("which of
my apps can do the most harm?") goes unanswered.

With the cap, reaching High or Critical requires something qualitatively
different: special access, a combination rule, poor provenance, a context
mismatch, or a threat-intelligence hit. Verified by
`engineInvariantSuite.permission_subtotal_cap_holds`.

---

## A-03 · "Special" is this project's protection level, not Android's

AOSP has three protection levels: normal, dangerous, signature. This project
adds a fourth label, `SPECIAL`, for access granted through a dedicated Settings
screen or an AppOps flag (`SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`,
`PACKAGE_USAGE_STATS`, `MANAGE_EXTERNAL_STORAGE`).

The platform reports several of these as "signature" or even "normal", which
badly understates what they allow — overlay access is reported as signature but
is what fake login screens are built from. The catalog's classification
therefore wins over the device's for exactly this set.

---

## A-04 · The device's protection level beats the catalog's

Except for A-03's special set, the protection level read from
`PackageManager.getPermissionInfo` always wins over the catalog's value. OEMs do
redefine permissions, and the device is the authority on its own platform. The
catalog only fills gaps.

---

## A-05 · An unknown permission scores zero

Custom and vendor permissions resolve to `Protection.UNKNOWN` and
`Category.UNCATEGORIZED`, whose multiplier and weight are both zero.

Guessing DANGEROUS would let any app inflate its own score by declaring a custom
permission. Guessing NORMAL would hide real vendor permissions. Zero is the
honest value for "we do not know what this does", and the UI says so.

---

## A-06 · The trusted-tool allowlist is tiny and unpinned

Five entries, no certificate pins. See `docs/LIMITATIONS.md` L-05. The effect is
that device admin is almost never excused, which errs toward over-flagging — the
safe direction for a security tool.

---

## A-07 · System apps are excluded by default

PS-01 asks for user-installed apps with a toggle. Default off, because including
them floods the list with OEM packages that look alarming and cannot be removed,
burying the apps a user can actually act on.

---

## A-08 · Only the worst context mismatch scores

An app can trip several mismatch rules at once (a torch holding SMS *and*
contacts *and* camera). These describe one underlying oddity — "this is not what
it says it is" — so only the highest-severity one contributes points. The rest
are listed for the user at zero points.

---

## A-09 · A band floor lifts the score to match

When a rule forces a band above what the arithmetic produced, the score is
raised to that band's lower bound. Showing "36 / CRITICAL" reads as a bug and
costs the user their trust in every other number on screen.

`RiskResult.rawScore` keeps the pre-lift value for calibration.

---

## A-10 · `INTERNET` and `RECEIVE_BOOT_COMPLETED` are checked as declared

Both are install-time `normal` permissions: always granted when declared, so
"granted" carries no information. Combination rules check declaration for these
two and grant state for everything else.

---

## A-11 · Combination predicates require ENABLED special access

A rule says what an app *can do right now*. Firing on declared-but-not-granted
access would overstate the case in the rule's own reason text, which is the
thing a judge reads aloud.

---

## A-12 · Six-hourly periodic sweep

WorkManager's floor is 15 minutes. Six hours is used because a missed install
surfacing within a few hours is adequate for a tool that is not a live
antivirus, and more frequent full scans are a real battery cost.

---

## A-13 · JSON file rather than Room for snapshots

PS-01 allows either. The data is a small map read once and written once per
scan; a database adds a schema, a migration path and an Android dependency that
would stop `:store` being pure Kotlin and instantly testable. Writes go via a
temp file and rename so a crash mid-write leaves the previous good file intact.

---

## A-14 · Logistic regression over a tree ensemble

Its contribution to a prediction is literally `weight x feature`, so the
features that moved a score can be named honestly. A gradient-boosted model
would likely score marginally higher and could not explain itself, which is the
opposite of what PS-01 asks for. `train.py` fits both and prints the gap; on the
synthetic data logistic regression was in fact *better* (F1 0.9942 vs 0.9920).

---

## A-15 · The ML feature vector is binary declared-permission presence

That is how public permission datasets are built. Introducing a
granted/declared distinction at inference time would mean scoring against a
different feature space than was trained on. Asserted by
`mlSuite.declared_permissions_set_their_feature_regardless_of_grant_state`.

---

## A-16 · compileSdk and targetSdk 36

AGP 9.4 supports up to API 37. API 36 was chosen as the most recent level
confidently believed stable. Raise both in `gradle/libs.versions.toml` if 37 is
stable when you build.

---

## A-17 · Notification threshold is High

Notifying on Medium would fire for a large share of ordinary apps on a first
sweep. A channel people mute protects nobody.

---

## A-18 · The debug activity is exported and is the launcher entry

With no UI in this run, `DebugDumpActivity` is both the launcher icon and
adb-drivable. **Unexport it and remove the LAUNCHER filter** once real screens
exist; an exported activity that triggers a full device scan is not something to
ship.

---

## A-19 · Consent for hash lookups is per-install, not per-scan

Once granted it persists until revoked. Asking on every scan would train people
to tap through it, which is how consent dialogs stop meaning anything.

---

## A-20 · Backup is disabled entirely

The snapshot store holds an inventory of every app on the device and what each
can reach. That is precisely the sort of record that should not be copied into a
cloud backup, even the user's own.
