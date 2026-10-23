# :permissions (M5)

*Android*

Declared vs granted extraction with protection levels.

## Public API
`PermissionExtractor.extract(packageInfo | packageName)` → `List<PermissionRecord>`,
and `extractGrouped` for the per-app category breakdown.

## Design notes
The declared/granted distinction comes from two index-aligned arrays:
`requestedPermissions` and `requestedPermissionsFlags`. A surprising amount of
code ignores the flags array and reports everything an app *asks for* as
something it *has*, which overstates risk on every modern app.

An ungranted **signature** permission is marked `grantable = false` and scores
zero: it can never be granted to that app, so it is not latent risk.

Special-access permissions are forced to `Protection.SPECIAL` regardless of what
the platform reports — Android calls `SYSTEM_ALERT_WINDOW` "signature", which
badly understates what it allows.

## Tests
```bash
./gradlew :permissions:testDebugUnitTest
```
