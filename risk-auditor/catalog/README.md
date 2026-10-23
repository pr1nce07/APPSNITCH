# :catalog (M1)

*pure Kotlin*

Permission → category, sensitivity and plain-language description.

## Public API
| Type | Role |
|---|---|
| `CatalogEntry` | permission, category, protection, sensitivity 0..1, plain text, API range, `special` |
| `PermissionCatalog` | `categoryOf`, `sensitivityOf`, `plainTextOf`, `enrich`, `summarise` |
| `CategorySummary` | device-wide rollup: "14 apps can read your SMS" |
| `CatalogLoader` | reads the bundled `permission_catalog.json` |

## Design notes
- The catalog takes its entries in the **constructor**, not from a file, so it
  has no I/O or serialization dependency and can be built directly in tests.
- `protection` here is a **fallback**. The level read from the device at scan
  time wins, because OEMs redefine permissions. The one exception is special
  access (see `docs/ASSUMPTIONS.md` A-03).
- `plainText` must never contain the permission constant; a test enforces it.
- An unknown permission returns `UNCATEGORIZED` and sensitivity 0. It never
  throws and never guesses.

## Tests
```bash
./gradlew :catalog:test
```
Also covered by `tools/offline-verify/run.sh` (8 assertions, executed).
