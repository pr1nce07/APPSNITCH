# 3-minute demo script

PS-01 deliverable 04. Judged on the **organizer test kit**, on the **judging
device** — apps nobody on this team has seen.

Everything below is written to work on an unknown device. There are no package
names, no "and here you can see our torch fixture", and no step that depends on
a particular app being present. If the demo only works on our phone, it does not
work.

---

## Before you start

| | |
|---|---|
| **0:00** | APK already installed. Do **not** install during the demo. |
| | Airplane mode **ON**. It proves offline-first and removes a failure mode. |
| | Screen brightness up, notifications cleared, battery saver off. |
| | Do **not** pre-scan the kit. The first scan happens on stage. |
| | Have `adb logcat -s RiskAuditorDump` already running on the laptop. |

If the kit has nothing Critical on it, that is fine. Say so, and demo the
highest-scoring app instead. A tool that finds nothing on a clean device is
working correctly, and saying that out loud is more convincing than a staged
red screen.

---

## The script

### 0:00 – 0:25 · Scan

Open the app. The scan runs.

> "This is scanning every app on your device. It's reading what each one is
> allowed to do — permissions, and the special access that's granted through
> separate settings screens. The phone is in airplane mode. Nothing leaves it."

Point at the count as it finishes.

**Why airplane mode matters:** it is the shortest possible proof of PS-01's
privacy and offline constraints. Do not skip it.

---

### 0:25 – 1:00 · Q1 — which apps can do the most harm

The device summary appears: band counts, then "N apps can read your text
messages", "N apps can use your camera".

> "That's the whole device at a glance. Below it, every app ranked worst first.
> The ranking is the point — if everything looked dangerous, this list would be
> useless."

Scroll once. Point at the top entry.

> "This is the app to look at first."

**Q1 answered.** If a judge interrupts here and asks "why is that one top?",
that is the demo going well — move straight to the next section.

---

### 1:00 – 2:00 · Q2 — why is it flagged

Tap the top app.

Read **two** reasons aloud, verbatim. Do not paraphrase into jargon — the whole
design goal is that the words on screen already make sense.

> "It says: *'It can read everything on your screen and tap for you.'* And:
> *'It can draw on top of other apps.'* Those two together are how banking
> trojans work — read what you type into your real bank app, then cover it with
> a fake one."

Scroll to the category breakdown.

> "Here's the detail. Note it separates what the app **has** from what it has
> only **asked for** — those are very different, and most tools blur them."

If any row says *"we could not check this on your phone"*, point at it:

> "And where we genuinely couldn't read a setting on this particular phone, it
> says so rather than guessing. Reporting 'no access' when the truth is 'we
> couldn't tell' is how a security tool gets someone hurt."

**Q2 answered.** This is the strongest 60 seconds you have. Do not rush it.

---

### 2:00 – 2:30 · Q3 — what can I do right now

Scroll to the actions.

> "And here's what to do about it, in the order it actually has to happen."

Tap through to the system settings screen, then come straight back.

> "If an app is a device administrator, Android flatly refuses to uninstall it.
> If it has accessibility access, it can tap 'cancel' on its own removal. So the
> steps are ordered: turn those off first, then remove. Getting that order wrong
> leaves someone stuck in a loop blaming themselves."

**Q3 answered.**

---

### 2:30 – 3:00 · One differentiator

Pick **one**. Have the second ready if the first does not apply on this kit.

**Option A — context mismatch (strongest, if the kit has one).**
Find a simple utility holding sensitive access.

> "This one claims to be a torch. It can read your text messages. Nothing about
> a torch needs that. It's not that the permission is rare — it's that it
> doesn't fit what the app says it is."

**Option B — the ranking holds up.**
Scroll to a legitimate, permission-heavy app sitting at Medium.

> "This messaging app holds more permissions than anything else on the device
> and it's Medium, not Critical — because it genuinely needs them. Permissions
> alone are capped so they can never push an app past Medium. Getting to High
> needs special access, a dangerous combination, or access that doesn't match
> what the app claims to be. That's what stops every app looking red."

**Option C — permission diff.** Only if a rescan is set up and something
changed. Do not attempt live otherwise.

---

## If something goes wrong

| Problem | Do this |
|---|---|
| Scan is slow | Keep talking: "it's reading every app on the device, nothing cached". Never tap repeatedly. |
| Nothing is Critical | Say so. Demo the highest scorer. A clean device scoring clean is the tool working. |
| An app crashes | Relaunch once. If it fails again, switch to `adb logcat -s RiskAuditorDump` and talk through the JSON output. |
| A judge asks "is this app actually malware?" | **"No — this says what an app *could* do, not what it *is* doing. It's a prioritiser, not a verdict."** Never claim more. |
| A judge asks about the ML model | Say it is trained on a public permission dataset, shown beside the rule score, and never overrides it — the rules are the source of every explanation. If it is still the synthetic model, say that plainly. |
| Asked about false positives | "The context engine only acts on strong classifications, and anything it isn't sure about produces no finding at all. We'd rather miss one than flag your actual banking app." |

---

## Lines not to say

- ❌ "This app is malware." → ✅ "This app can do a lot of harm."
- ❌ "We detected a trojan." → ✅ "This combination is what trojans use."
- ❌ "It's 100% accurate." → ✅ "It reads what Android reports. It doesn't run the app."
- ❌ Anything about our own fixture apps. The kit is theirs.

---

## Rehearsal checklist

- [ ] Run it twice, timed, on a device nobody pre-scanned.
- [ ] Rehearse Option B as well as Option A — the kit may have no utility app.
- [ ] Practise reading reasons **verbatim**. Reverting to jargon under pressure
      undoes the entire Q2 argument.
- [ ] Know the top three numbers cold: permission cap 45, bands 30/60/80, and
      how many apps were scanned.
- [ ] Decide in advance who drives the phone and who talks. Not the same person.
