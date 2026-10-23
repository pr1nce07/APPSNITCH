package com.riskauditor.context

import com.riskauditor.model.AppRecord

/**
 * `ApplicationInfo.category` constants (API 26+), reproduced here so that this
 * module stays Android-free. Values are stable platform constants.
 */
object AppCategoryConstants {
    const val UNDEFINED = -1
    const val GAME = 0
    const val AUDIO = 1
    const val VIDEO = 2
    const val IMAGE = 3
    const val SOCIAL = 4
    const val NEWS = 5
    const val MAPS = 6
    const val PRODUCTIVITY = 7
    const val ACCESSIBILITY = 8
}

/** How confident the classifier is. Only [STRONG] matches produce mismatch findings. */
enum class ClassificationConfidence { STRONG, WEAK, NONE }

data class Classification(
    val archetype: Archetype,
    val confidence: ClassificationConfidence,
    /** The token that matched, for reason evidence. */
    val matchedOn: String? = null
)

/**
 * Guesses what an app claims to be, from its label and package name.
 *
 * Design rules that keep this from producing nonsense on a device full of apps we
 * have never seen:
 *
 *  1. **Whole-token matching only.** Substring matching is how you get "Calculator
 *     Vault" classified as a calculator and, worse, "Telegram" matching "gram".
 *     Labels and package names are split on non-letters first.
 *  2. **A single keyword is WEAK.** Only a keyword match that is corroborated — by
 *     a second token, or by `ApplicationInfo.category` — is STRONG.
 *  3. **Ambiguity collapses to UNKNOWN.** If two archetypes match equally, we know
 *     nothing, and an app we know nothing about must never be flagged.
 */
class ArchetypeClassifier(
    private val keywords: Map<Archetype, Set<String>> = DEFAULT_KEYWORDS
) {

    fun classify(app: AppRecord): Classification {
        val tokens = tokenise(app.label) + tokenise(app.packageName)
        if (tokens.isEmpty()) return Classification(Archetype.UNKNOWN, ClassificationConfidence.NONE)

        val hits: Map<Archetype, List<String>> = keywords
            .mapValues { (_, words) -> tokens.filter { it in words } }
            .filterValues { it.isNotEmpty() }

        if (hits.isEmpty()) {
            // No keyword at all. Fall back to the declared category, which the
            // developer set and which is a weaker but real signal.
            return fromDeclaredCategory(app)
        }

        // Rank by number of distinct matching tokens; ties mean ambiguity.
        val ranked = hits.entries.sortedByDescending { it.value.distinct().size }
        val best = ranked.first()
        val bestCount = best.value.distinct().size

        val tiedWithBest = ranked.count { it.value.distinct().size == bestCount }
        if (tiedWithBest > 1) {
            return Classification(Archetype.UNKNOWN, ClassificationConfidence.NONE)
        }

        val declared = fromDeclaredCategory(app)
        val corroboratedByCategory =
            declared.archetype != Archetype.UNKNOWN && declared.archetype == best.key

        val confidence = when {
            bestCount >= 2 -> ClassificationConfidence.STRONG
            corroboratedByCategory -> ClassificationConfidence.STRONG
            else -> ClassificationConfidence.WEAK
        }

        return Classification(best.key, confidence, best.value.distinct().sorted().joinToString(","))
    }

    private fun fromDeclaredCategory(app: AppRecord): Classification = when (app.appCategory) {
        AppCategoryConstants.GAME ->
            Classification(Archetype.GAME, ClassificationConfidence.WEAK, "app_category=game")
        AppCategoryConstants.ACCESSIBILITY ->
            Classification(Archetype.UNKNOWN, ClassificationConfidence.NONE, "app_category=accessibility")
        AppCategoryConstants.MAPS ->
            Classification(Archetype.NAVIGATION, ClassificationConfidence.WEAK, "app_category=maps")
        AppCategoryConstants.IMAGE ->
            Classification(Archetype.GALLERY_OR_PHOTO_EDITOR, ClassificationConfidence.WEAK, "app_category=image")
        AppCategoryConstants.AUDIO, AppCategoryConstants.VIDEO ->
            Classification(Archetype.MUSIC_OR_VIDEO_PLAYER, ClassificationConfidence.WEAK, "app_category=media")
        AppCategoryConstants.SOCIAL ->
            Classification(Archetype.MESSENGER, ClassificationConfidence.WEAK, "app_category=social")
        else ->
            Classification(Archetype.UNKNOWN, ClassificationConfidence.NONE)
    }

    /** Split on anything that is not a letter, lowercase, drop noise tokens. */
    private fun tokenise(text: String): Set<String> =
        text.split(Regex("[^\\p{L}]+"))
            .asSequence()
            .map { it.lowercase() }
            .filter { it.length >= 3 }
            .filter { it !in STOP_TOKENS }
            .toSet()

    companion object {
        /** Package-name noise and generic words that must never drive a classification. */
        private val STOP_TOKENS = setOf(
            "com", "org", "net", "android", "app", "apps", "the", "and", "for",
            "pro", "free", "lite", "plus", "mobile", "google", "best", "new",
            "inc", "ltd", "llc", "studio", "studios", "dev", "io"
        )

        val DEFAULT_KEYWORDS: Map<Archetype, Set<String>> = mapOf(
            Archetype.TORCH to setOf("torch", "flashlight", "flash", "light", "lamp", "luz"),
            Archetype.CALCULATOR to setOf("calculator", "calc", "calculate", "kalkulator"),
            Archetype.WALLPAPER to setOf("wallpaper", "wallpapers", "background", "backgrounds", "livewallpaper", "theme", "themes"),
            Archetype.CLOCK_OR_ALARM to setOf("clock", "alarm", "timer", "stopwatch", "countdown"),
            Archetype.NOTES to setOf("notes", "notepad", "memo", "todo", "checklist", "diary", "journal"),
            Archetype.QR_SCANNER to setOf("scanner", "barcode", "qrcode"),
            Archetype.FILE_MANAGER to setOf("files", "filemanager", "explorer", "cleaner", "storage"),
            Archetype.GALLERY_OR_PHOTO_EDITOR to setOf("gallery", "photo", "photos", "editor", "collage", "selfie"),
            Archetype.GAME to setOf("game", "games", "puzzle", "racing", "arcade", "clash", "craft", "quest"),
            Archetype.KEYBOARD to setOf("keyboard", "keypad", "typing", "ime", "swipe"),
            Archetype.MESSENGER to setOf("messenger", "messages", "messaging", "chat", "chats", "sms", "text", "talk", "whatsapp", "telegram", "signal"),
            Archetype.BROWSER to setOf("browser", "chrome", "firefox", "opera", "webview"),
            Archetype.BANKING_OR_PAYMENT to setOf("bank", "banking", "wallet", "pay", "payment", "payments", "upi", "finance", "card"),
            Archetype.SECURITY_OR_ANTIVIRUS to setOf("security", "antivirus", "virus", "malware", "guard", "defender", "protect", "protection", "shield", "mdm", "endpoint"),
            Archetype.NAVIGATION to setOf("maps", "map", "navigation", "navigator", "gps", "route", "traffic"),
            Archetype.CAMERA to setOf("camera", "cam", "lens"),
            Archetype.MUSIC_OR_VIDEO_PLAYER to setOf("music", "player", "video", "audio", "radio", "podcast", "stream"),
            Archetype.FITNESS to setOf("fitness", "health", "workout", "steps", "pedometer", "running", "tracker")
        )

        val DEFAULT = ArchetypeClassifier()
    }
}
