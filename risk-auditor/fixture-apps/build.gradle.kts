plugins {
    alias(libs.plugins.android.application)
}

/*
 * DEV-ONLY INERT TEST FIXTURES. NEVER SHIPPED.
 *
 * Each flavor builds a tiny APK that DECLARES permissions and components but
 * DOES NOTHING. Every component here is an empty no-op: no network, no file
 * access, no data collection, no behaviour of any kind.
 *
 * This is how PS-01's "no live malware" constraint is honoured while still
 * being able to demonstrate — and regression-test — the detection of a torch
 * app holding SMS access, an accessibility+overlay pair, a hidden-icon app and
 * so on. Downloading real malware to test a scanner would be both against the
 * rules and genuinely reckless on a shared judging device.
 *
 * Build and install all fixtures:  scripts/install_fixtures.sh
 */
android {
    namespace = "com.riskauditor.fixtures"
    compileSdk = libs.versions.compileSdk.get().toInt()

    buildFeatures {
        resValues = true
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    flavorDimensions += "fixture"

    productFlavors {
        // -- Context mismatch cases: a simple utility holding sensitive access --
        create("torchSms") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.torchsms"
            resValue("string", "fixture_label", "Super Torch Flashlight")
        }
        create("calculatorContacts") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.calccontacts"
            resValue("string", "fixture_label", "Simple Calculator Calc")
        }
        create("wallpaperCameraMic") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.wallpapercammic"
            resValue("string", "fixture_label", "Live Wallpapers HD Backgrounds")
        }

        // -- Special access cases --
        create("accessibility") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.accessibility"
            resValue("string", "fixture_label", "Fixture Accessibility")
        }
        create("overlay") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.overlay"
            resValue("string", "fixture_label", "Fixture Overlay")
        }
        create("deviceAdmin") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.deviceadmin"
            resValue("string", "fixture_label", "Fixture Device Admin")
        }
        create("notificationListener") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.notiflistener"
            resValue("string", "fixture_label", "Fixture Notification Listener")
        }
        create("installPackagesBoot") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.installboot"
            resValue("string", "fixture_label", "Fixture Installer")
        }

        // -- Provenance and manifest cases --
        create("noLauncherIcon") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.nolauncher"
            resValue("string", "fixture_label", "Fixture Headless")
        }
        create("debuggable") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.debuggable"
            resValue("string", "fixture_label", "Fixture Debuggable")
        }
        create("exportedCleartext") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.exportedcleartext"
            resValue("string", "fixture_label", "Fixture Exported")
        }

        // -- Negative cases: these MUST NOT be flagged --
        create("legitMessenger") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.messenger"
            resValue("string", "fixture_label", "Fixture Chat Messenger")
        }
        create("clean") {
            dimension = "fixture"
            applicationId = "com.riskauditor.fixtures.clean"
            resValue("string", "fixture_label", "Fixture Clean App")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Deliberately none. A fixture must stay as close to an empty APK as
    // possible so that what the scanner sees is exactly what the manifest says.
}
