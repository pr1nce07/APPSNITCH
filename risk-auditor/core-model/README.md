# :core-model (M0)

The shared contract. Every other module speaks in these types; nothing here
imports Android, so the whole model is testable on the JVM in milliseconds.

## Purpose

One serializable, versioned vocabulary for "what we know about an installed app"
and "what we concluded about it". Keeping this module Android-free is what lets
the scoring engine be exercised against hand-built fixtures with no device.

## Public API

| Type | Role |
|---|---|
| `SCHEMA_VERSION` | Stamped on every `AppSnapshot`; gates diffing against old data |
| `Category` | The nine PS-01 categories + `ACCOUNTS_CALENDAR_SENSORS_NEARBY` + `UNCATEGORIZED` |
| `Protection` | `NORMAL` / `DANGEROUS` / `SIGNATURE` / `SPECIAL` / `UNKNOWN` |
| `InstallerKind`, `InstallerInfo` | Provenance; `isUntrustedSource` drives rubric modifiers |
| `AppRecord` | Identity + provenance of one package. Holds **no** Drawable/Bitmap/Context |
| `PermissionRecord` | One requested permission: declared, granted, protection, category |
| `SpecialAccessKind`, `AccessState`, `SpecialAccessState` | Special access, with `ENABLED` vs `DECLARED_ONLY` kept distinct |
| `ManifestFindings`, `ExportedComponent` | Static analysis output (stretch 12) |
| `HashVerdict` | Sealed; defaults to `SkippedNoConsent` (stretch 11) |
| `MlResult` | Shown beside the rule score, never instead of it (stretch 13) |
| `AppSnapshot` | The scan unit: what `:store` persists and `:diff` compares |
| `Reason`, `RiskResult`, `ScoredApp`, `DeviceScan` | Scoring output |
| `ModelJson` | The one `Json` configuration: `strict`, `lenient`, `pretty` |

## Design decisions worth knowing

- **`versionCode` is a `Long`.** `PackageInfo.versionCode` overflowed `Int` for
  real apps years ago; `getLongVersionCode()` is the correct source.
- **`AccessState.UNKNOWN` exists on purpose.** AppOps cannot always be read for
  another package (see `docs/LIMITATIONS.md` L-03). The model refuses to pretend
  that "could not determine" means "not granted", and the UI must say
  "declares overlay access" rather than "has overlay access" in that case.
- **`RiskResult` validates its own score** in `init`. An out-of-range score is a
  programming error in the engine, not something to render.
- **`ModelJson.lenient` ignores unknown keys** so a snapshot written by a newer
  build still loads; `ModelJson.strict` rejects them so schema drift fails a test
  rather than silently degrading at runtime.

## Tests

```bash
./gradlew :core-model:test
```

Covers: full round trip; unknown-field tolerance (lenient) and rejection (strict);
schema-version stamping; polymorphic `HashVerdict` for all seven variants;
`RiskResult` range validation; the nine mandated categories; band-floor
monotonicity; and a golden file at
`src/test/resources/golden/app_snapshot_v1.json` that must keep decoding.
