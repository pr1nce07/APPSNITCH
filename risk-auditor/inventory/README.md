# :inventory (M4)

*Android*

Enumerates installed apps into `AppRecord`s.

## Public API
| Type | Role |
|---|---|
| `PackageSource` | the thin seam over `PackageManager`; everything goes through it |
| `RealPackageSource` | the real implementation |
| `AppInventory` | `scan(options)` → `InventoryResult`; `scanOne(package)` |
| `IconLoader` | lazy, LRU-cached icon loading |
| `InventoryOptions` | `includeSystemApps` (default **off**) |

## Design notes
- **No icons in the model.** A `Drawable` holds a `Context`, which would make
  `AppRecord` unserializable and leak memory, and decoding 200 bitmaps before
  showing anything is a visible freeze.
- **One bad package never aborts a scan.** Apps genuinely disappear between
  enumeration and query; failures land in `InventoryResult.skipped` and are
  surfaced, not hidden.
- `getInstalledPackages` falls back to a lighter flag set if the first call
  throws, which some OEM builds do with very large package counts.

## Tests
```bash
./gradlew :inventory:testDebugUnitTest
```
Uses `FakePackageSource`, which builds `PackageInfo` directly so a test can
express states a real device makes awkward — such as a package that throws
mid-scan.
