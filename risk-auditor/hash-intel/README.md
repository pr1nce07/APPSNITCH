# :hash-intel (M8)

*pure Kotlin*

APK fingerprinting and optional threat-intelligence lookups.

## **The only module in this project permitted to perform network I/O.**
OkHttp appears here and nowhere else. `scripts/check_traceability.py` fails the
build if a networking library shows up in another module's build file.

## Public API
| Type | Role |
|---|---|
| `Sha256Hasher` | streaming SHA-256, cached by (package, update time, size) |
| `ConsentManager`, `ConsentStore` | default-OFF, explicit, revocable |
| `ThreatIntelProvider` | `LocalBlocklistProvider` (offline), `VirusTotalProvider`, `MalwareBazaarProvider` |
| `ThreatIntelService` | enforces consent, handles rate limits, degrades offline |

## Privacy
The consent gate lives in `ThreatIntelService`, not in each provider, so adding
a provider cannot accidentally bypass it. A lookup sends a 64-character
fingerprint and nothing else — no package name, no device identifier, no APK
bytes. `ThreatIntelServiceTest` asserts this by inspecting the outgoing request
against a MockWebServer.

A single detection maps to SUSPICIOUS, not MALICIOUS: one engine flagging a file
is routinely a false positive, and MALICIOUS carries a CRITICAL band floor.

MalwareBazaar needs a free `Auth-Key` (verified against bazaar.abuse.ch on
2026-10-16). No API key is shipped with the app.

## Tests
```bash
./gradlew :hash-intel:test
```
