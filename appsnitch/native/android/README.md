# AppScanner plugin (Android side)

`AppScannerPlugin.kt` is the Capacitor bridge. It calls `AuditorFacade.scanForAppSnitch()`
from risk-auditor, which returns the ScanResult JSON the UI already understands.

Not yet compiled against Capacitor or the risk-auditor modules (see INTEGRATION.md).
