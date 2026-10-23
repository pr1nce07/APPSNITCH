plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

/*
 * THE ONLY MODULE IN THIS PROJECT PERMITTED TO PERFORM NETWORK I/O.
 *
 * OkHttp appears here and nowhere else. If a dependency on this module or on
 * okhttp shows up in another module's build file, that is a privacy regression
 * and should be treated as a build break, not a style issue.
 */
dependencies {
    api(project(":core-model"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
