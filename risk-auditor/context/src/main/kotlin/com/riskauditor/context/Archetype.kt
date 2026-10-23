package com.riskauditor.context

import com.riskauditor.model.Category

/**
 * What an app claims to be.
 *
 * Classification is a *heuristic over the app's own label and package name*, which
 * is the only purpose signal available offline. The Play Store category is not
 * readable without network access (see `docs/LIMITATIONS.md` L-04), so
 * `ApplicationInfo.category` is used where the developer set it and keyword
 * matching fills the rest.
 *
 * The whole point of this enum is [UNKNOWN]. Most apps are not classifiable from a
 * name, and an archetype we are not sure about must produce *no* finding at all.
 * A false "torch app reading your SMS" on a judging device is far more damaging
 * than a missed one.
 */
enum class Archetype(val displayName: String) {
    TORCH("a torch app"),
    CALCULATOR("a calculator"),
    WALLPAPER("a wallpaper app"),
    CLOCK_OR_ALARM("a clock or alarm app"),
    NOTES("a notes app"),
    QR_SCANNER("a QR or barcode scanner"),
    FILE_MANAGER("a file manager"),
    GALLERY_OR_PHOTO_EDITOR("a photo app"),
    GAME("a game"),
    KEYBOARD("a keyboard"),
    MESSENGER("a messaging app"),
    BROWSER("a browser"),
    BANKING_OR_PAYMENT("a banking or payment app"),
    SECURITY_OR_ANTIVIRUS("a security app"),
    NAVIGATION("a maps or navigation app"),
    CAMERA("a camera app"),
    MUSIC_OR_VIDEO_PLAYER("a music or video app"),
    FITNESS("a fitness app"),
    UNKNOWN("an app");

    /**
     * Categories this archetype plausibly needs.
     *
     * Deliberately generous: the question is not "should a torch have location?"
     * but "is this access so far outside the app's stated job that a normal person
     * would be surprised?". Being generous here is what keeps false positives down.
     */
    val expectedCategories: Set<Category>
        get() = when (this) {
            TORCH -> setOf(Category.NETWORK, Category.BACKGROUND_AND_PERSISTENCE)
            CALCULATOR -> setOf(Category.NETWORK)
            WALLPAPER -> setOf(Category.NETWORK, Category.STORAGE_AND_MEDIA, Category.BACKGROUND_AND_PERSISTENCE)
            CLOCK_OR_ALARM -> setOf(Category.NETWORK, Category.BACKGROUND_AND_PERSISTENCE, Category.LOCATION)
            NOTES -> setOf(Category.NETWORK, Category.STORAGE_AND_MEDIA, Category.ACCOUNTS_CALENDAR_SENSORS_NEARBY)
            QR_SCANNER -> setOf(Category.NETWORK, Category.CAMERA_AND_MICROPHONE, Category.STORAGE_AND_MEDIA)
            FILE_MANAGER -> setOf(Category.NETWORK, Category.STORAGE_AND_MEDIA, Category.BACKGROUND_AND_PERSISTENCE)
            GALLERY_OR_PHOTO_EDITOR -> setOf(
                Category.NETWORK, Category.STORAGE_AND_MEDIA,
                Category.CAMERA_AND_MICROPHONE, Category.LOCATION
            )
            GAME -> setOf(Category.NETWORK, Category.STORAGE_AND_MEDIA, Category.BACKGROUND_AND_PERSISTENCE)
            KEYBOARD -> setOf(
                Category.NETWORK, Category.STORAGE_AND_MEDIA,
                Category.CAMERA_AND_MICROPHONE, Category.CONTACTS_AND_CALL_LOGS
            )
            // A messenger genuinely needs almost everything. It gets no mismatch findings.
            MESSENGER -> Category.entries.toSet() - Category.SPECIAL_ACCESS
            BROWSER -> Category.entries.toSet() - Category.SPECIAL_ACCESS - Category.SMS
            BANKING_OR_PAYMENT -> setOf(
                Category.NETWORK, Category.PHONE_AND_IDENTITY, Category.CAMERA_AND_MICROPHONE,
                Category.STORAGE_AND_MEDIA, Category.LOCATION, Category.CONTACTS_AND_CALL_LOGS,
                Category.SMS, Category.BACKGROUND_AND_PERSISTENCE,
                Category.ACCOUNTS_CALENDAR_SENSORS_NEARBY
            )
            SECURITY_OR_ANTIVIRUS -> Category.entries.toSet()
            NAVIGATION -> setOf(
                Category.NETWORK, Category.LOCATION, Category.STORAGE_AND_MEDIA,
                Category.BACKGROUND_AND_PERSISTENCE, Category.ACCOUNTS_CALENDAR_SENSORS_NEARBY
            )
            CAMERA -> setOf(
                Category.NETWORK, Category.CAMERA_AND_MICROPHONE,
                Category.STORAGE_AND_MEDIA, Category.LOCATION
            )
            MUSIC_OR_VIDEO_PLAYER -> setOf(
                Category.NETWORK, Category.STORAGE_AND_MEDIA,
                Category.BACKGROUND_AND_PERSISTENCE, Category.CAMERA_AND_MICROPHONE
            )
            FITNESS -> setOf(
                Category.NETWORK, Category.LOCATION, Category.BACKGROUND_AND_PERSISTENCE,
                Category.ACCOUNTS_CALENDAR_SENSORS_NEARBY, Category.STORAGE_AND_MEDIA,
                Category.CAMERA_AND_MICROPHONE
            )
            // Unknown expects everything, so nothing is ever a mismatch.
            UNKNOWN -> Category.entries.toSet()
        }

    /**
     * True for archetypes whose job is so narrow that *any* sensitive access is
     * surprising. These are the ones PS-01 names: torch, calculator, wallpaper.
     */
    val isSimpleUtility: Boolean
        get() = this == TORCH || this == CALCULATOR || this == WALLPAPER ||
            this == CLOCK_OR_ALARM || this == QR_SCANNER || this == NOTES

    /** Archetypes that never produce a finding, however broad their access. */
    val isExemptFromMismatch: Boolean
        get() = this == UNKNOWN || this == SECURITY_OR_ANTIVIRUS || this == MESSENGER
}
