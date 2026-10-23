package com.riskauditor.context

import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.Category
import com.riskauditor.model.Severity

/**
 * One finding: this app claims to be X but holds access that X has no use for.
 *
 * @param category the category that does not fit.
 * @param grantedPermissions the permission constants behind it (evidence, not headline).
 */
data class ContextMismatch(
    val archetype: Archetype,
    val category: Category,
    val severity: Severity,
    val grantedPermissions: List<String>,
    val matchedOn: String?
) {
    /** Plain-language sentence. No permission constants — those live in evidence. */
    fun headline(): String = "This looks like ${archetype.displayName} but it can ${categoryVerb()}"

    private fun categoryVerb(): String = when (category) {
        Category.SMS -> "read your text messages"
        Category.CONTACTS_AND_CALL_LOGS -> "read your contacts and call history"
        Category.CAMERA_AND_MICROPHONE -> "use your camera and microphone"
        Category.LOCATION -> "see where you are"
        Category.PHONE_AND_IDENTITY -> "make calls and identify your phone"
        Category.STORAGE_AND_MEDIA -> "read your files and photos"
        Category.SPECIAL_ACCESS -> "do things normally reserved for system apps"
        Category.BACKGROUND_AND_PERSISTENCE -> "keep itself running in the background"
        Category.NETWORK -> "send data over the internet"
        Category.ACCOUNTS_CALENDAR_SENSORS_NEARBY -> "read your calendar, sensors or nearby devices"
        Category.UNCATEGORIZED -> "do something we could not categorise"
    }
}

data class ContextAnalysis(
    val classification: Classification,
    val mismatches: List<ContextMismatch>
) {
    val hasMismatch: Boolean get() = mismatches.isNotEmpty()

    val worst: ContextMismatch?
        get() = mismatches.maxByOrNull { it.severity.ordinal }

    companion object {
        fun none(classification: Classification) = ContextAnalysis(classification, emptyList())
    }
}

/**
 * PS-01 should-have 08: flag apps whose label does not fit their access.
 *
 * The engine is intentionally conservative in three ways, because a false positive
 * on the judging device costs more than a miss:
 *
 *  1. Only [ClassificationConfidence.STRONG] classifications produce findings.
 *  2. Archetypes that legitimately need broad access ([Archetype.isExemptFromMismatch])
 *     never produce findings at all.
 *  3. Only *granted* permissions count. A torch app that merely declares SMS
 *     access without holding it is scored by the base rules, not flagged here.
 */
class ContextMismatchEngine(
    private val classifier: ArchetypeClassifier = ArchetypeClassifier.DEFAULT
) {

    fun analyse(snapshot: AppSnapshot): ContextAnalysis {
        val classification = classifier.classify(snapshot.app)

        if (classification.confidence != ClassificationConfidence.STRONG) {
            return ContextAnalysis.none(classification)
        }
        if (classification.archetype.isExemptFromMismatch) {
            return ContextAnalysis.none(classification)
        }

        val expected = classification.archetype.expectedCategories
        val mismatches = mutableListOf<ContextMismatch>()

        SENSITIVE_CATEGORIES.forEach { category ->
            if (category in expected) return@forEach

            val granted = snapshot.permissions
                .filter { it.granted && it.category == category }
                .map { it.name }

            if (granted.isEmpty()) return@forEach

            mismatches += ContextMismatch(
                archetype = classification.archetype,
                category = category,
                severity = severityFor(classification.archetype, category),
                grantedPermissions = granted.sorted(),
                matchedOn = classification.matchedOn
            )
        }

        // Special access is handled separately: it is not a "permission granted"
        // in the ordinary sense, so it is read off the special-access state.
        val enabledSpecial = snapshot.specialAccess.enabledKinds
        if (Category.SPECIAL_ACCESS !in expected && enabledSpecial.isNotEmpty()) {
            mismatches += ContextMismatch(
                archetype = classification.archetype,
                category = Category.SPECIAL_ACCESS,
                severity = Severity.CRITICAL,
                grantedPermissions = enabledSpecial.map { it.name }.sorted(),
                matchedOn = classification.matchedOn
            )
        }

        return ContextAnalysis(classification, mismatches.sortedByDescending { it.severity.ordinal })
    }

    private fun severityFor(archetype: Archetype, category: Category): Severity = when {
        category == Category.SPECIAL_ACCESS -> Severity.CRITICAL
        category == Category.SMS && archetype.isSimpleUtility -> Severity.CRITICAL
        category == Category.CONTACTS_AND_CALL_LOGS && archetype.isSimpleUtility -> Severity.HIGH
        category == Category.CAMERA_AND_MICROPHONE && archetype.isSimpleUtility -> Severity.HIGH
        archetype.isSimpleUtility -> Severity.MEDIUM
        else -> Severity.MEDIUM
    }

    companion object {
        /**
         * Categories worth checking for a mismatch. Network and background are
         * excluded: almost every app has them and flagging them would be noise.
         */
        private val SENSITIVE_CATEGORIES = listOf(
            Category.SMS,
            Category.CONTACTS_AND_CALL_LOGS,
            Category.CAMERA_AND_MICROPHONE,
            Category.LOCATION,
            Category.PHONE_AND_IDENTITY,
            Category.STORAGE_AND_MEDIA
        )

        val DEFAULT = ContextMismatchEngine()
    }
}
