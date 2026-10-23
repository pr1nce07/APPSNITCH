package com.riskauditor.presentation

import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.catalog.PermissionCatalog
import com.riskauditor.model.AccessState
import com.riskauditor.model.Category
import com.riskauditor.model.DeviceScan
import com.riskauditor.model.MlLabel
import com.riskauditor.model.RiskBand
import com.riskauditor.model.ScoredApp
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.trust.InstallerClassifier

/**
 * Builds the two UI-ready views from a completed scan.
 *
 * No UI, no Android. Everything here is deterministic given a [DeviceScan], which
 * makes ordering and grouping testable without a screen.
 */
class ReportBuilder(
    private val catalog: PermissionCatalog = CatalogLoader.default
) {

    // ---------------------------------------------------------------- Q1 ----

    fun deviceReport(scan: DeviceScan): DeviceReport {
        // Worst first. Ties break by label so the order is stable between scans —
        // a list that reshuffles on every rescan looks broken.
        val ranked = scan.apps
            .sortedWith(
                compareByDescending<ScoredApp> { it.risk.score }
                    .thenBy { it.snapshot.app.isSystemOrUpdatedSystem }
                    .thenBy { it.label.lowercase() }
            )
            .mapIndexed { index, scored ->
                RankedApp(
                    rank = index + 1,
                    packageName = scored.packageName,
                    label = scored.label,
                    score = scored.risk.score,
                    band = scored.risk.band,
                    topReason = scored.risk.topReason?.headline,
                    isSystem = scored.snapshot.app.isSystemOrUpdatedSystem,
                    installSource = InstallerClassifier.displayName(scored.snapshot.app.installer.kind)
                )
            }

        val counts = BandCounts(
            low = scan.apps.count { it.risk.band == RiskBand.LOW },
            medium = scan.apps.count { it.risk.band == RiskBand.MEDIUM },
            high = scan.apps.count { it.risk.band == RiskBand.HIGH },
            critical = scan.apps.count { it.risk.band == RiskBand.CRITICAL }
        )

        val perApp = scan.apps.associate { it.packageName to it.snapshot.permissions }
        val labels = scan.apps.associate { it.packageName to it.label }

        val rollups = catalog.summarise(perApp)
            .filter { !it.isEmpty }
            .map { summary ->
                CategoryRollup(
                    category = summary.category,
                    displayName = summary.category.displayName,
                    appsWithGranted = summary.appsWithGranted,
                    appsWithDeclaredOnly = summary.appsWithDeclaredOnly,
                    grantedPermissionCount = summary.grantedPermissionCount,
                    worstOffenders = summary.worstOffenders.map { labels[it] ?: it },
                    sentence = rollupSentence(summary.category, summary.appsWithGranted)
                )
            }
            .sortedWith(compareByDescending<CategoryRollup> { it.appsWithGranted }.thenBy { it.category.ordinal })

        val roster = scan.apps.flatMap { scored ->
            scored.snapshot.specialAccess.entries
                .filter { it.state == AccessState.ENABLED || it.state == AccessState.DECLARED_ONLY }
                .map { entry ->
                    SpecialAccessHolder(
                        kind = entry.kind,
                        packageName = scored.packageName,
                        label = scored.label,
                        state = entry.state
                    )
                }
        }.sortedWith(
            compareBy<SpecialAccessHolder> { it.kind.ordinal }
                .thenByDescending { it.state == AccessState.ENABLED }
                .thenBy { it.label.lowercase() }
        )

        return DeviceReport(
            scannedAt = scan.scannedAt,
            appCount = scan.apps.size,
            includedSystemApps = scan.includedSystemApps,
            bandCounts = counts,
            ranked = ranked,
            categoryRollups = rollups,
            specialAccessRoster = roster,
            skipped = scan.skipped,
            headline = headline(counts, scan.apps.size)
        )
    }

    private fun headline(counts: BandCounts, total: Int): String = when {
        total == 0 -> "No apps were scanned."
        counts.needingAttention == 0 -> "Scanned $total apps. Nothing needs your attention."
        counts.needingAttention == 1 -> "Scanned $total apps. 1 needs your attention."
        else -> "Scanned $total apps. ${counts.needingAttention} need your attention."
    }

    private fun rollupSentence(category: Category, apps: Int): String {
        val verb = when (category) {
            Category.SPECIAL_ACCESS -> "have system-level access"
            Category.SMS -> "can read your text messages"
            Category.CONTACTS_AND_CALL_LOGS -> "can read your contacts or call history"
            Category.CAMERA_AND_MICROPHONE -> "can use your camera or microphone"
            Category.LOCATION -> "can see where you are"
            Category.PHONE_AND_IDENTITY -> "can identify your phone or make calls"
            Category.STORAGE_AND_MEDIA -> "can read your files or photos"
            Category.BACKGROUND_AND_PERSISTENCE -> "can keep running in the background"
            Category.NETWORK -> "can send data online"
            Category.ACCOUNTS_CALENDAR_SENSORS_NEARBY -> "can read your calendar, sensors or nearby devices"
            Category.UNCATEGORIZED -> "ask for access we could not categorise"
        }
        return if (apps == 1) "1 app $verb" else "$apps apps $verb"
    }

    // ---------------------------------------------------------------- Q2/Q3 ----

    fun appDetail(
        scored: ScoredApp,
        recentChanges: List<String> = emptyList()
    ): AppDetail {
        val snapshot = scored.snapshot
        val risk = scored.risk

        val groups = Category.entries.mapNotNull { category ->
            val permissions = snapshot.permissions.filter { it.category == category }
            if (permissions.isEmpty()) return@mapNotNull null

            CategoryGroup(
                category = category,
                displayName = category.displayName,
                points = risk.categoryScores.firstOrNull { it.category == category }?.points ?: 0,
                permissions = permissions
                    .sortedWith(
                        compareByDescending<com.riskauditor.model.PermissionRecord> { it.granted }
                            .thenByDescending { catalog.sensitivityOf(it.name) }
                            .thenBy { it.name }
                    )
                    .map { it.toRow(catalog.plainTextOf(it.name)) }
            )
        }.sortedWith(compareByDescending<CategoryGroup> { it.points }.thenBy { it.category.ordinal })

        val specialRows = snapshot.specialAccess.entries
            .filter { it.state != AccessState.NOT_PRESENT }
            .sortedWith(
                compareByDescending<com.riskauditor.model.SpecialAccessEntry> { it.state == AccessState.ENABLED }
                    .thenBy { it.kind.ordinal }
            )
            .map { entry ->
                SpecialAccessRow(
                    kind = entry.kind,
                    displayName = entry.kind.displayName,
                    state = entry.state,
                    sentence = specialSentence(entry.kind, entry.state),
                    evidence = entry.evidence
                )
            }

        // The ML label and the rule band can disagree. When they do, the UI says
        // so rather than quietly showing two numbers that imply different things.
        val mlDisagrees = snapshot.ml?.let { ml ->
            val rulesSayRisky = risk.band == RiskBand.HIGH || risk.band == RiskBand.CRITICAL
            val modelSaysRisky = ml.label == MlLabel.FLAGGED
            rulesSayRisky != modelSaysRisky
        } ?: false

        return AppDetail(
            header = AppHeader(
                packageName = snapshot.packageName,
                label = snapshot.app.label,
                versionName = snapshot.app.versionName,
                firstInstallTime = snapshot.app.firstInstallTime,
                lastUpdateTime = snapshot.app.lastUpdateTime,
                installSource = InstallerClassifier.displayName(snapshot.app.installer.kind),
                targetSdk = snapshot.app.targetSdk,
                isSystem = snapshot.app.isSystemOrUpdatedSystem,
                hasLauncherIcon = snapshot.app.hasLauncherIcon
            ),
            score = risk.score,
            band = risk.band,
            whyFlagged = risk.reasons,
            categoryGroups = groups,
            specialAccess = specialRows,
            actions = RemediationAdvisor.adviseFor(snapshot, risk),
            ml = snapshot.ml,
            mlDisagreesWithRules = mlDisagrees,
            hashVerdict = snapshot.hashVerdict,
            apkSha256 = snapshot.apkSha256,
            manifest = snapshot.manifest,
            recentChanges = recentChanges
        )
    }

    private fun specialSentence(kind: SpecialAccessKind, state: AccessState): String = when (state) {
        AccessState.ENABLED -> when (kind) {
            SpecialAccessKind.ACCESSIBILITY -> "It can read everything on your screen and tap for you."
            SpecialAccessKind.OVERLAY -> "It can draw on top of other apps."
            SpecialAccessKind.DEVICE_ADMIN -> "It has administrator control of your phone."
            SpecialAccessKind.NOTIFICATION_LISTENER -> "It can read all of your notifications."
            SpecialAccessKind.INSTALL_PACKAGES -> "It can install other apps."
            SpecialAccessKind.USAGE_STATS -> "It can see which apps you use and when."
            SpecialAccessKind.ALL_FILES_ACCESS -> "It can open every file on your phone."
            SpecialAccessKind.VPN_SERVICE -> "It can route all of your internet traffic through itself."
            SpecialAccessKind.BATTERY_OPTIMIZATION_EXEMPT -> "It is allowed to run without battery limits."
        }
        AccessState.DECLARED_ONLY -> "It has asked for this, but you have not given it."
        // Honest wording: we could not read the setting, so we do not claim either way.
        AccessState.UNKNOWN -> "We could not check this on your phone."
        AccessState.NOT_PRESENT -> "It does not have this."
    }
}
