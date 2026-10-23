package com.riskauditor.scoring

import com.riskauditor.model.Category
import com.riskauditor.model.SpecialAccessKind

/**
 * Plain-language wording for categories and special access.
 *
 * Every string here is written for a reader with no technical background: no
 * permission constants, no Android vocabulary, no "may be able to". Permission
 * constants belong in `Reason.evidence`, which the UI shows only in the
 * collapsible technical panel.
 *
 * Rule of thumb used throughout: say what the app *can do to the user*, not what
 * the operating system calls the capability.
 */
internal object CategoryPhrases {

    /** Headline for an app holding granted access in this category. */
    fun grantedHeadline(category: Category): String = when (category) {
        Category.SPECIAL_ACCESS -> "It has powerful system-level access"
        Category.SMS -> "It can read your text messages"
        Category.CONTACTS_AND_CALL_LOGS -> "It can read your contacts and call history"
        Category.CAMERA_AND_MICROPHONE -> "It can use your camera and microphone"
        Category.LOCATION -> "It can see where you are"
        Category.PHONE_AND_IDENTITY -> "It can identify your phone and make calls"
        Category.STORAGE_AND_MEDIA -> "It can read your files and photos"
        Category.BACKGROUND_AND_PERSISTENCE -> "It keeps itself running in the background"
        Category.NETWORK -> "It can send and receive data online"
        Category.ACCOUNTS_CALENDAR_SENSORS_NEARBY -> "It can read your calendar, sensors or nearby devices"
        Category.UNCATEGORIZED -> "It asks for access we could not categorise"
    }

    /** Headline for a category the app has asked for but has not been given. */
    fun latentHeadline(category: Category): String = when (category) {
        Category.SMS -> "It has asked to read your text messages"
        Category.CONTACTS_AND_CALL_LOGS -> "It has asked for your contacts and call history"
        Category.CAMERA_AND_MICROPHONE -> "It has asked for your camera and microphone"
        Category.LOCATION -> "It has asked to see where you are"
        Category.PHONE_AND_IDENTITY -> "It has asked to identify your phone"
        Category.STORAGE_AND_MEDIA -> "It has asked to read your files"
        else -> "It has asked for access it does not currently have"
    }

    fun detail(category: Category, grantedCount: Int): String = when (category) {
        Category.SPECIAL_ACCESS ->
            "This kind of access is granted through a separate settings screen, not a normal permission prompt, and it is what most banking scams rely on."
        Category.SMS ->
            "Text messages are where banks and other services send one-time login codes."
        Category.CONTACTS_AND_CALL_LOGS ->
            "Your address book and call history show who you know and how often you speak to them, which is what scam and extortion operations want."
        Category.CAMERA_AND_MICROPHONE ->
            "An app with this access can take photos and record sound without showing anything on screen."
        Category.LOCATION ->
            "This lets the app build a record of where you go."
        Category.PHONE_AND_IDENTITY ->
            "This includes details that identify your specific phone, which can be used to track you across apps."
        Category.STORAGE_AND_MEDIA ->
            "This covers documents, downloads and photos you have saved."
        Category.BACKGROUND_AND_PERSISTENCE ->
            "An app that restarts itself and runs unseen can keep working long after you forget it is installed."
        Category.NETWORK ->
            "On its own this is normal and almost every app has it. It matters because it is how anything collected leaves your phone."
        Category.ACCOUNTS_CALENDAR_SENSORS_NEARBY ->
            "This covers your schedule, health readings and the devices around you."
        Category.UNCATEGORIZED ->
            "These are permissions defined by the app itself or by your phone's maker, so we cannot say what they allow."
    }.let { base ->
        if (grantedCount > 1) "$base It holds $grantedCount such permissions." else base
    }

    fun specialAccessHeadline(kind: SpecialAccessKind): String = when (kind) {
        SpecialAccessKind.ACCESSIBILITY -> "It can read everything on your screen and tap for you"
        SpecialAccessKind.OVERLAY -> "It can draw on top of other apps"
        SpecialAccessKind.DEVICE_ADMIN -> "It has administrator control of your phone"
        SpecialAccessKind.NOTIFICATION_LISTENER -> "It can read all of your notifications"
        SpecialAccessKind.INSTALL_PACKAGES -> "It can install other apps"
        SpecialAccessKind.USAGE_STATS -> "It can see which apps you use and when"
        SpecialAccessKind.ALL_FILES_ACCESS -> "It can open every file on your phone"
        SpecialAccessKind.VPN_SERVICE -> "It can route all of your internet traffic through itself"
        SpecialAccessKind.BATTERY_OPTIMIZATION_EXEMPT -> "It is allowed to run without battery limits"
    }

    fun specialAccessDetail(kind: SpecialAccessKind): String = when (kind) {
        SpecialAccessKind.ACCESSIBILITY ->
            "This is meant for apps that help people with disabilities. It lets an app see the text of any app you open, including your bank, and press buttons on your behalf."
        SpecialAccessKind.OVERLAY ->
            "An app with this can cover a real screen with one of its own, so what you type may not be going where you think."
        SpecialAccessKind.DEVICE_ADMIN ->
            "This is meant for work-device and security apps. It can also be used to stop you from uninstalling the app."
        SpecialAccessKind.NOTIFICATION_LISTENER ->
            "Notifications often show one-time codes and message previews before you open anything."
        SpecialAccessKind.INSTALL_PACKAGES ->
            "This lets the app put more software on your phone after you installed it."
        SpecialAccessKind.USAGE_STATS ->
            "Knowing exactly when you open a particular app is how a convincing fake screen is timed."
        SpecialAccessKind.ALL_FILES_ACCESS ->
            "This goes beyond photos: it covers documents, downloads and files other apps have saved."
        SpecialAccessKind.VPN_SERVICE ->
            "Everything your phone sends and receives passes through an app with this access."
        SpecialAccessKind.BATTERY_OPTIMIZATION_EXEMPT ->
            "Your phone will not pause this app to save battery, so it can keep running indefinitely."
    }

    fun declaredOnlySuffix(): String =
        " It has asked for this but does not have it yet."
}
