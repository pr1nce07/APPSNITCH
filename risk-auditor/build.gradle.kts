plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Every JVM toolchain in the build is pinned to 17 (AGP 9.4 requires JDK 17+).
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }
}

/**
 * Convenience aggregate: run every JVM unit test in the build.
 * Android instrumentation tests are NOT included here (they need a device).
 */
tasks.register("testAll") {
    group = "verification"
    description = "Runs all JVM unit tests across every module."
    dependsOn(subprojects.mapNotNull { sp ->
        sp.tasks.matching { it.name == "test" || it.name == "testDebugUnitTest" }
    })
}
