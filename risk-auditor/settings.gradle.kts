pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "risk-auditor"

// ---- Pure Kotlin/JVM modules (no Android APIs; millisecond tests) ----
include(":core-model")      // M0  shared contract
include(":catalog")         // M1  permission -> category catalog
include(":trust")           // M2  installer classification, MDM allowlist, roles
include(":context")         // M9  context-mismatch engine
include(":scoring")         // M10 risk engine + rubric.json
include(":store")           // M12 snapshot persistence
include(":diff")            // M12 permission diff engine
include(":presentation")    // M13 UI-ready models (no UI)
include(":ml")              // M11 on-device inference (training pipeline in ml/training)
include(":hash-intel")      // M8  SHA-256 + threat intel (ONLY module with network code)

// ---- Android modules ----
include(":inventory")           // M4  app inventory
include(":permissions")         // M5  declared vs granted extraction
include(":special-access")      // M6  accessibility / admin / listener / appops
include(":manifest-analysis")   // M7  static manifest checks
include(":actions")             // M14 remediation advisor + intent factory
include(":monitor")             // M15 install monitoring + notifications
include(":report")              // M16 PDF export
include(":app")                 // M17 integration + headless debug entry point

// ---- Dev-only inert test fixtures (never shipped) ----
include(":fixture-apps")        // M3
