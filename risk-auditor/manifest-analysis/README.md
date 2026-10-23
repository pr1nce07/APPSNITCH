# :manifest-analysis (M7)

*Android*

Static inspection of installed APKs (PS-01 stretch 12).

## Public API
`ManifestAnalyzer.analyse(packageName | packageInfo)` → `ManifestFindings`:
exported components by type, unguarded providers, cleartext traffic, debuggable,
allowBackup, testOnly.

## Design notes
Uses `PackageManager` component flags rather than parsing the APK's binary XML.
`AssetManager.openXmlResourceParser` can read the manifest but is slow, needs the
APK path to still be valid, and duplicates work the platform already did.
`ComponentInfo.exported` already reflects the resolved value, including the
targetSdk-31 default change.

Providers are reported separately because they are the dangerous case: an
unguarded exported provider can expose an app's entire database to any other app
with no user interaction.

`ManifestFindings.NOT_ANALYSED` is distinguishable from a clean result. "Nothing
found" and "we could not look" must never render the same.

## Tests
```bash
./gradlew :manifest-analysis:testDebugUnitTest
```
