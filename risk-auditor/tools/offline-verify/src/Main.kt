package verify

import kotlin.system.exitProcess

/**
 * Offline verification harness entry point.
 *
 * Runs the pure-Kotlin logic of :core-model, :catalog, :trust, :context and
 * :scoring against the REAL bundled data files, with no third-party dependencies.
 *
 * This is NOT a replacement for the JUnit suites in each module — it cannot test
 * serialization, Android APIs, or anything needing Robolectric. It exists because
 * the authoring environment has no access to Maven Central, and shipping
 * unexecuted scoring logic would be worse than shipping a narrower proof.
 */
fun main() {
    println("=".repeat(62))
    println("  On-Device App Risk Auditor — offline verification harness")
    println("  Pure-Kotlin logic only. No Android, no serialization.")
    println("=".repeat(62))

    enumSerialNameSuite()
    catalogSuite()
    trustSuite()
    contextSuite()
    rubricConfigSuite()
    engineProfileSuite()
    engineInvariantSuite()

    pipelineSuite()
    mlSuite(goldenPath = System.getProperty("golden.vectors") ?: "ml/src/test/resources/ml/golden_vectors.json")

    calibrationTable()

    exitProcess(Check.summary())
}
