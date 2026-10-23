package com.riskauditor.trust

import com.riskauditor.model.InstallerInfo
import com.riskauditor.model.InstallerKind

/**
 * Turns a raw installer package name into an [InstallerKind].
 *
 * Why this is not a one-line map lookup:
 *
 *  - On API 30+ Android reports three attributions. `installingPackage` is set by
 *    whoever performed the install and an app can install itself; `initiatingPackage`
 *    is recorded by the system and is the harder one to forge. When the two
 *    disagree we trust the initiator, because the weaker claim is the one an
 *    attacker controls.
 *  - A null installer is NOT the same as "sideloaded from a file manager". It
 *    usually means `adb install`, or an OS that dropped the record. Both are
 *    untrusted, but they are different facts and the reason text should say so.
 *
 * Everything here is name matching, which is a *hint only*. Name matching alone
 * never confers trust — see [TrustRegistry], which requires a pinned signing
 * certificate or a trusted store as well.
 */
object InstallerClassifier {

    /** Google Play. The only first-party store. */
    private const val GOOGLE_PLAY = "com.android.vending"

    /** OEM app stores. Pre-installed and signed by the device maker. */
    private val OEM_STORES = setOf(
        "com.sec.android.app.samsungapps",      // Samsung Galaxy Store
        "com.samsung.android.app.galaxyapps",   // Samsung (older naming)
        "com.huawei.appmarket",                 // Huawei AppGallery
        "com.xiaomi.market",                    // Xiaomi GetApps
        "com.xiaomi.mipicks",                   // Xiaomi GetApps (global)
        "com.oppo.market",                      // OPPO App Market
        "com.heytap.market",                    // OPPO/realme (HeyTap)
        "com.vivo.appstore",                    // vivo App Store
        "com.bbk.appstore",                     // vivo (older naming)
        "com.transsion.phoenix",                // Transsion
        "com.amazon.venezia",                   // Amazon Appstore
        "com.amazon.mShop.android.shopping"     // Amazon shopping app (installs Appstore apps)
    )

    /** Recognised third-party stores that are neither Play nor OEM. */
    private val OTHER_STORES = setOf(
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "com.aurora.store",
        "com.github.yeriomin.yalpstore"
    )

    /**
     * Packages that perform a *sideload*: the system installer UI, file managers
     * and browsers. An app arriving through one of these had no store review.
     */
    private val SIDELOAD_CONDUITS = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.shell",
        "com.google.android.apps.nbu.files",    // Files by Google
        "com.android.documentsui",
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.sec.android.app.myfiles",
        "com.mi.android.globalFileexplorer",
        "com.android.bluetooth",                // received over Bluetooth
        "com.google.android.gm",                // email attachment
        "com.whatsapp",
        "org.telegram.messenger"
    )

    /**
     * Classify from the three attributions Android can report.
     *
     * @param installingPackage `InstallSourceInfo.getInstallingPackageName()`, or
     *        `getInstallerPackageName()` below API 30.
     * @param initiatingPackage `InstallSourceInfo.getInitiatingPackageName()`, API 30+ only.
     */
    fun classify(
        installingPackage: String?,
        initiatingPackage: String? = null
    ): InstallerKind {
        // Prefer the system-recorded initiator when it tells us something useful.
        val initiatorKind = initiatingPackage?.let { kindOf(it) }
        if (initiatorKind != null && initiatorKind != InstallerKind.UNKNOWN) {
            return initiatorKind
        }

        if (installingPackage.isNullOrBlank()) return InstallerKind.ADB_OR_NONE
        return kindOf(installingPackage)
    }

    /** Convenience over the model type. */
    fun classify(info: InstallerInfo): InstallerKind =
        classify(info.installingPackage, info.initiatingPackage)

    /** Fill in [InstallerInfo.kind] from the raw attributions. */
    fun enrich(info: InstallerInfo): InstallerInfo = info.copy(kind = classify(info))

    private fun kindOf(packageName: String): InstallerKind = when (packageName) {
        GOOGLE_PLAY -> InstallerKind.GOOGLE_PLAY
        in OEM_STORES -> InstallerKind.OEM_STORE
        in OTHER_STORES -> InstallerKind.OTHER_STORE
        in SIDELOAD_CONDUITS -> InstallerKind.SIDELOADED
        else -> InstallerKind.UNKNOWN
    }

    /**
     * Human-readable source name for the technical panel. Never used in a headline,
     * because the package name means nothing to a non-technical user.
     */
    fun displayName(kind: InstallerKind): String = when (kind) {
        InstallerKind.GOOGLE_PLAY -> "Google Play"
        InstallerKind.OEM_STORE -> "your phone maker's app store"
        InstallerKind.OTHER_STORE -> "another app store"
        InstallerKind.SIDELOADED -> "a file, browser or message, not an app store"
        InstallerKind.ADB_OR_NONE -> "an unrecorded source, such as a computer"
        InstallerKind.UNKNOWN -> "an app store we do not recognise"
    }
}
