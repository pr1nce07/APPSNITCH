@file:Suppress("unused")

/*
 * Annotation stubs for the OFFLINE VERIFICATION HARNESS ONLY.
 *
 * This file is NEVER part of the Gradle build and never ships. It exists purely
 * so the real production sources can be compiled in an environment with no access
 * to Maven Central, where the real kotlinx-serialization artifacts cannot be
 * downloaded.
 *
 * The real build uses the genuine kotlinx.serialization library. Compiling the
 * production sources against these stubs proves the LOGIC is correct; it proves
 * nothing about serialization behaviour, which is covered by the JUnit tests in
 * each module (`./gradlew :core-model:test`) and is explicitly listed as NOT
 * VERIFIED HERE in docs/PROGRESS.md.
 */

package kotlinx.serialization

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.ANNOTATION_CLASS
)
@Retention(AnnotationRetention.RUNTIME)
annotation class Serializable

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.ANNOTATION_CLASS
)
@Retention(AnnotationRetention.RUNTIME)
annotation class SerialName(val value: String)
