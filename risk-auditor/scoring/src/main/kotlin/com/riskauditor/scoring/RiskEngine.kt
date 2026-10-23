package com.riskauditor.scoring

import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.catalog.PermissionCatalog
import com.riskauditor.context.ContextAnalysis
import com.riskauditor.context.ContextMismatchEngine
import com.riskauditor.model.AccessState
import com.riskauditor.model.AppSnapshot
import com.riskauditor.model.Category
import com.riskauditor.model.CategoryScore
import com.riskauditor.model.HashVerdict
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.Reason
import com.riskauditor.model.RiskBand
import com.riskauditor.model.RiskResult
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.trust.RoleHolders
import com.riskauditor.trust.TrustRegistry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything the engine needs beyond the snapshot itself. */
data class ScoringInput(
    val snapshot: AppSnapshot,
    val roleHolders: RoleHolders = RoleHolders.EMPTY,
    /** Used only to decide whether an install counts as "very recent". 0 disables that modifier. */
    val nowMillis: Long = 0L
)

/**
 * Turns an [AppSnapshot] into a 0-100 score, a band, and a ranked list of
 * plain-language reasons.
 *
 * ## The shape of the model, and why
 *
 * The one decision that matters most is the **permission subtotal cap**
 * ([Caps.permissionSubtotal], 45 points). Ordinary permissions, however many an
 * app holds, can never on their own push it past Medium. Reaching High or
 * Critical requires something qualitatively different: special access, a
 * combination rule, bad provenance, a context mismatch, or a threat-intel hit.
 *
 * Without that cap, a legitimate messaging app — which genuinely needs SMS,
 * contacts, camera, microphone, storage and phone access — outscores a torch app
 * holding one accessibility service, and the ranking that answers PS-01's Q1
 * becomes useless. Every app looks red, so no app looks red.
 *
 * ## Avoiding double counting
 *
 * Special access is scored from [AppSnapshot.specialAccess] only. A permission
 * such as `SYSTEM_ALERT_WINDOW` or `MANAGE_EXTERNAL_STORAGE` that is already
 * represented there is skipped in the permission loop, so one capability is never
 * counted twice. A permission whose special-access entry is absent (because the
 * detector did not run) still scores through the permission path, so nothing is
 * silently lost either.
 *
 * ## Reasons
 *
 * Every rule that moves the score emits its [Reason] at the same moment. There is
 * no second "explain" pass that could drift from the arithmetic.
 */
class RiskEngine(
    private val rubric: Rubric = RubricLoader.default,
    private val catalog: PermissionCatalog = CatalogLoader.default,
    private val trust: TrustRegistry = TrustRegistry.EMPTY,
    private val contextEngine: ContextMismatchEngine = ContextMismatchEngine.DEFAULT
) {

    /** Permission -> the special access that already accounts for it. */
    private val coveredBySpecialAccess: Map<String, SpecialAccessKind> = mapOf(
        "android.permission.SYSTEM_ALERT_WINDOW" to SpecialAccessKind.OVERLAY,
        "android.permission.REQUEST_INSTALL_PACKAGES" to SpecialAccessKind.INSTALL_PACKAGES,
        "android.permission.PACKAGE_USAGE_STATS" to SpecialAccessKind.USAGE_STATS,
        "android.permission.MANAGE_EXTERNAL_STORAGE" to SpecialAccessKind.ALL_FILES_ACCESS,
        "android.permission.BIND_ACCESSIBILITY_SERVICE" to SpecialAccessKind.ACCESSIBILITY,
        "android.permission.BIND_DEVICE_ADMIN" to SpecialAccessKind.DEVICE_ADMIN,
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" to SpecialAccessKind.NOTIFICATION_LISTENER,
        "android.permission.BIND_VPN_SERVICE" to SpecialAccessKind.VPN_SERVICE,
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to SpecialAccessKind.BATTERY_OPTIMIZATION_EXEMPT
    )

    fun score(snapshot: AppSnapshot): RiskResult = score(ScoringInput(snapshot))

    fun score(input: ScoringInput): RiskResult {
        val snapshot = input.snapshot
        val reasons = mutableListOf<Reason>()
        var bandFloor: RiskBand? = null
        var bandFloorRule: String? = null

        fun raiseFloor(band: RiskBand?, rule: String) {
            if (band == null) return
            if (bandFloor == null || band.ordinal > bandFloor!!.ordinal) {
                bandFloor = band
                bandFloorRule = rule
            }
        }

        // ---- 1. Ordinary permission categories -------------------------------
        val categoryScores = scorePermissions(snapshot, input.roleHolders, reasons)
        val rawPermissionTotal = categoryScores.sumOf { it.points.toDouble() }
        val permissionTotal = min(rawPermissionTotal, rubric.caps.permissionSubtotal)

        // ---- 2. Special access ----------------------------------------------
        val specialTotal = scoreSpecialAccess(snapshot, reasons)

        // ---- 3. Provenance ---------------------------------------------------
        val provenanceTotal = scoreProvenance(input, reasons)

        // ---- 4. Static manifest findings -------------------------------------
        val manifestTotal = scoreManifest(snapshot, reasons)

        // ---- 5. Threat intelligence ------------------------------------------
        val intelTotal = scoreThreatIntel(snapshot, reasons, ::raiseFloor)

        // ---- 6. Combination rules --------------------------------------------
        val comboTotal = scoreCombinations(snapshot, reasons, ::raiseFloor)

        // ---- 7. Context mismatch ---------------------------------------------
        val context = contextEngine.analyse(snapshot)
        val contextTotal = scoreContext(context, reasons, ::raiseFloor)

        // ---- 8. Totals, system dampening, clamp -------------------------------
        var raw = permissionTotal + specialTotal + provenanceTotal +
            manifestTotal + intelTotal + comboTotal + contextTotal

        if (snapshot.app.isSystemOrUpdatedSystem) {
            val before = raw
            raw *= rubric.reducers.systemAppDampening
            if (before - raw >= 1.0) {
                reasons += Reason(
                    code = "REDUCER_SYSTEM_APP",
                    severity = Severity.INFO,
                    headline = "This app came with your phone",
                    detail = "Apps built into the phone are expected to hold broad access, so this one's score has been reduced. You usually cannot remove them.",
                    evidence = listOf("is_system=${snapshot.app.isSystem}", "is_updated_system=${snapshot.app.isUpdatedSystem}"),
                    points = -(before - raw).roundToInt()
                )
            }
        }

        val rawRounded = raw.roundToInt()
        var clamped = rawRounded.coerceIn(0, 100)
        val naturalBand = rubric.bandFor(clamped)
        var band = naturalBand
        bandFloor?.let { band = band.atLeast(it) }

        // When a rule forces a higher band than the arithmetic produced, lift the
        // number to the bottom of that band. Showing "36 / CRITICAL" reads as a
        // bug and costs the user their trust in every other number on the screen.
        val floorApplied = band != naturalBand
        if (floorApplied) {
            clamped = maxOf(clamped, rubric.bands.lowerBoundOf(band))
        }

        // ---- 9. Reason hygiene ------------------------------------------------
        var finalReasons = reasons
            .filter { it.points != 0 || it.severity >= Severity.HIGH }
            .sortedWith(compareByDescending<Reason> { it.points }.thenByDescending { it.severity.ordinal })

        if (band.requiresMinimumReasons && finalReasons.size < rubric.minimumReasonsForHighOrCritical) {
            finalReasons = padReasons(
                existing = finalReasons,
                snapshot = snapshot,
                target = rubric.minimumReasonsForHighOrCritical
            )
        }

        return RiskResult(
            score = clamped,
            band = band,
            reasons = finalReasons,
            categoryScores = categoryScores,
            bandFloorRule = if (floorApplied) bandFloorRule else null,
            rawScore = rawRounded
        )
    }

    // -------------------------------------------------------------------------
    // 1. Permissions
    // -------------------------------------------------------------------------

    private fun scorePermissions(
        snapshot: AppSnapshot,
        roles: RoleHolders,
        reasons: MutableList<Reason>
    ): List<CategoryScore> {
        val scores = mutableListOf<CategoryScore>()

        val scorable = snapshot.permissions.filter { record ->
            // Skip anything already represented in the special-access state,
            // so one capability is never counted twice.
            val covering = coveredBySpecialAccess[record.name]
            covering == null ||
                snapshot.specialAccess.stateOf(covering) == AccessState.NOT_PRESENT
        }

        Category.entries.forEach { category ->
            if (category == Category.UNCATEGORIZED) return@forEach

            val inCategory = scorable.filter { it.category == category }
            if (inCategory.isEmpty()) return@forEach

            val weight = rubric.weightFor(category)
            if (weight.base <= 0.0) return@forEach

            // Strongest permission counts fully; each further one is discounted,
            // so breadth within a category cannot outweigh depth across categories.
            val ranked = inCategory
                .map { it to rawPoints(it, weight.base) }
                .sortedByDescending { it.second }

            var subtotal = 0.0
            ranked.forEachIndexed { index, (_, points) ->
                subtotal += points * pow(weight.diminishing, index)
            }

            var capped = min(subtotal, weight.cap)
            if (category == Category.NETWORK) {
                capped = min(capped, rubric.caps.networkCategory)
            }

            // A role holder genuinely needs its category: the default SMS app must
            // hold SMS permissions, and scoring it like a torch app would flood
            // the High band and make the ranking worthless.
            val excusingRole = roles.excusingRole(snapshot.packageName, category)
            if (excusingRole != null) {
                capped *= rubric.reducers.roleHolderCategory
            }

            val grantedCount = inCategory.count { it.granted }
            val points = capped.roundToInt()

            scores += CategoryScore(
                category = category,
                points = points,
                grantedCount = grantedCount,
                declaredCount = inCategory.size
            )

            if (points <= 0) return@forEach

            val granted = inCategory.filter { it.granted }
            val headline = if (granted.isNotEmpty()) {
                CategoryPhrases.grantedHeadline(category)
            } else {
                CategoryPhrases.latentHeadline(category)
            }

            val detail = buildString {
                append(CategoryPhrases.detail(category, grantedCount))
                if (granted.isEmpty()) append(CategoryPhrases.declaredOnlySuffix())
                if (excusingRole != null) {
                    append(" This is expected because it is ${excusingRole.displayName}.")
                }
            }

            reasons += Reason(
                code = "CATEGORY_${category.name}",
                severity = severityForCategoryPoints(points),
                headline = headline,
                detail = detail,
                evidence = inCategory.sortedBy { it.name }.map {
                    "${it.name} (${if (it.granted) "granted" else "declared"}, ${it.protection.name.lowercase()})"
                },
                points = points
            )
        }

        return scores
    }

    /** Points for one permission before diminishing returns and caps. */
    private fun rawPoints(record: PermissionRecord, categoryBase: Double): Double {
        val sensitivity = catalog.sensitivityOf(record.name)
        if (sensitivity <= 0.0) return 0.0

        val protectionMultiplier = rubric.protectionMultiplier(record.protection)
        val stateMultiplier = when {
            record.granted -> rubric.stateMultipliers.granted
            !record.grantable -> rubric.stateMultipliers.notGrantable
            record.declared -> rubric.stateMultipliers.declaredNotGranted
            else -> 0.0
        }

        return categoryBase * sensitivity * protectionMultiplier * stateMultiplier
    }

    // -------------------------------------------------------------------------
    // 2. Special access
    // -------------------------------------------------------------------------

    private fun scoreSpecialAccess(snapshot: AppSnapshot, reasons: MutableList<Reason>): Double {
        var subtotal = 0.0

        snapshot.specialAccess.entries
            .filter { it.state == AccessState.ENABLED || it.state == AccessState.DECLARED_ONLY }
            .sortedByDescending { rubric.specialWeight(it.kind).enabled }
            .forEach { entry ->
                val weight = rubric.specialWeight(entry.kind)
                var points = if (entry.state == AccessState.ENABLED) weight.enabled else weight.declaredOnly
                if (points <= 0.0) return@forEach

                // A verified security or management tool is supposed to hold these.
                var reducedBy: String? = null
                when (entry.kind) {
                    SpecialAccessKind.DEVICE_ADMIN ->
                        if (trust.deviceAdminIsExpected(snapshot.app)) {
                            points *= rubric.reducers.verifiedSecurityToolDeviceAdmin
                            reducedBy = trust.evaluate(snapshot.app).displayName
                        }
                    SpecialAccessKind.ACCESSIBILITY ->
                        if (trust.accessibilityIsExpected(snapshot.app)) {
                            points *= rubric.reducers.verifiedAccessibilityTool
                            reducedBy = trust.evaluate(snapshot.app).displayName
                        }
                    else -> Unit
                }

                subtotal += points

                val detail = buildString {
                    append(CategoryPhrases.specialAccessDetail(entry.kind))
                    if (entry.state == AccessState.DECLARED_ONLY) append(CategoryPhrases.declaredOnlySuffix())
                    if (reducedBy != null) {
                        append(" This is expected for $reducedBy, so it has been scored lower.")
                    }
                }

                val headline = if (entry.state == AccessState.ENABLED) {
                    CategoryPhrases.specialAccessHeadline(entry.kind)
                } else {
                    "It has asked for " + CategoryPhrases.specialAccessHeadline(entry.kind)
                        .removePrefix("It ").replaceFirstChar { it.lowercase() }
                }

                reasons += Reason(
                    code = "SPECIAL_${entry.kind.name}_${entry.state.name}",
                    severity = if (entry.state == AccessState.ENABLED) Severity.CRITICAL else Severity.MEDIUM,
                    headline = headline,
                    detail = detail,
                    evidence = listOfNotNull(entry.evidence, entry.component),
                    points = points.roundToInt()
                )
            }

        return min(subtotal, rubric.caps.specialAccessSubtotal)
    }

    // -------------------------------------------------------------------------
    // 3. Provenance
    // -------------------------------------------------------------------------

    private fun scoreProvenance(input: ScoringInput, reasons: MutableList<Reason>): Double {
        val app = input.snapshot.app
        var total = 0.0

        val (installerPoints, installerReason) = when (app.installer.kind) {
            InstallerKind.SIDELOADED -> rubric.provenance.sideloaded to
                ("PROVENANCE_SIDELOADED" to "It did not come from an app store")
            InstallerKind.ADB_OR_NONE -> rubric.provenance.adbOrNone to
                ("PROVENANCE_NO_INSTALL_RECORD" to "Your phone has no record of where this app came from")
            InstallerKind.UNKNOWN -> rubric.provenance.unknownInstaller to
                ("PROVENANCE_UNKNOWN_INSTALLER" to "It was installed by something we do not recognise")
            InstallerKind.GOOGLE_PLAY -> rubric.provenance.googlePlay to
                ("PROVENANCE_PLAY" to "It came from Google Play")
            InstallerKind.OEM_STORE -> rubric.provenance.oemStore to
                ("PROVENANCE_OEM_STORE" to "It came from your phone maker's app store")
            InstallerKind.OTHER_STORE -> 0 to ("PROVENANCE_OTHER_STORE" to "It came from another app store")
        }

        if (installerPoints != 0) {
            total += installerPoints
            reasons += Reason(
                code = installerReason.first,
                severity = if (installerPoints > 0) Severity.MEDIUM else Severity.INFO,
                headline = installerReason.second,
                detail = if (installerPoints > 0) {
                    "Apps that arrive outside an app store have not been through any review. That does not make this app bad, but nothing has checked it."
                } else {
                    "Apps from a store have been through some review before being published."
                },
                evidence = listOfNotNull(
                    app.installer.installingPackage?.let { "installing=$it" },
                    app.installer.initiatingPackage?.let { "initiating=$it" }
                ),
                points = installerPoints
            )
        }

        if (app.predatesRuntimePermissions) {
            total += rubric.provenance.targetSdkBelow23
            reasons += Reason(
                code = "PROVENANCE_OLD_TARGET_SDK",
                severity = Severity.HIGH,
                headline = "It was built for a much older version of Android",
                detail = "Apps this old are given everything they ask for at install time, without the prompts your phone would normally show you.",
                evidence = listOf("targetSdk=${app.targetSdk}"),
                points = rubric.provenance.targetSdkBelow23
            )
        }

        if (!app.hasLauncherIcon) {
            total += rubric.provenance.noLauncherIcon
            reasons += Reason(
                code = "PROVENANCE_NO_LAUNCHER_ICON",
                severity = Severity.HIGH,
                headline = "It does not appear in your app list",
                detail = "This app has no icon to tap, so you would not notice it while scrolling through your apps. Some legitimate apps work this way, but so does monitoring software.",
                evidence = listOf("has_launcher_icon=false"),
                points = rubric.provenance.noLauncherIcon
            )
        }

        if (input.nowMillis > 0 && app.firstInstallTime > 0) {
            val hours = (input.nowMillis - app.firstInstallTime) / 3_600_000.0
            if (hours in 0.0..rubric.provenance.recentInstallWindowHours.toDouble()) {
                total += rubric.provenance.installedWithinHours
                reasons += Reason(
                    code = "PROVENANCE_RECENT_INSTALL",
                    severity = Severity.LOW,
                    headline = "It was installed very recently",
                    detail = "Worth a second look simply because it is new. If you did not install it yourself, that matters.",
                    evidence = listOf("hours_since_install=${hours.roundToInt()}"),
                    points = rubric.provenance.installedWithinHours
                )
            }
        }

        return total
    }

    // -------------------------------------------------------------------------
    // 4. Manifest
    // -------------------------------------------------------------------------

    private fun scoreManifest(snapshot: AppSnapshot, reasons: MutableList<Reason>): Double {
        val findings = snapshot.manifest
        if (!findings.analysed) return 0.0
        var total = 0.0

        if (findings.debuggable) {
            total += rubric.manifest.debuggable
            reasons += Reason(
                code = "MANIFEST_DEBUGGABLE",
                severity = Severity.HIGH,
                headline = "It was shipped in developer mode",
                detail = "Apps released to the public should not be in this mode. It lets anyone with access to your unlocked phone inspect and change what the app is doing.",
                evidence = listOf("debuggable=true"),
                points = rubric.manifest.debuggable
            )
        }

        if (findings.cleartextTrafficAllowed == true) {
            total += rubric.manifest.cleartextTraffic
            reasons += Reason(
                code = "MANIFEST_CLEARTEXT",
                severity = Severity.MEDIUM,
                headline = "It can send data without encryption",
                detail = "Anything it sends unencrypted can be read by whoever runs the Wi-Fi network you are on.",
                evidence = listOf("cleartext_traffic_allowed=true"),
                points = rubric.manifest.cleartextTraffic
            )
        }

        if (findings.testOnly) {
            total += rubric.manifest.testOnly
            reasons += Reason(
                code = "MANIFEST_TEST_ONLY",
                severity = Severity.MEDIUM,
                headline = "It is marked as a test build",
                detail = "Test builds are not meant to be installed by normal users and cannot be published to an app store.",
                evidence = listOf("test_only=true"),
                points = rubric.manifest.testOnly
            )
        }

        val providers = findings.unguardedExportedProviders.size
        val others = findings.unguardedExported.size - providers
        val componentPoints = min(
            providers * rubric.manifest.unguardedExportedProvider +
                others * rubric.manifest.unguardedExportedComponent,
            rubric.manifest.maxUnguardedComponentPoints
        )

        if (componentPoints > 0) {
            total += componentPoints
            reasons += Reason(
                code = "MANIFEST_UNGUARDED_EXPORTED",
                severity = if (providers > 0) Severity.HIGH else Severity.MEDIUM,
                headline = "Other apps on your phone can reach inside it",
                detail = "Parts of this app are open to any other app with no permission required. " +
                    if (providers > 0) "That includes a data store, which may expose whatever it holds." else "",
                evidence = findings.unguardedExported.take(8).map { "${it.type.name.lowercase()}:${it.className}" },
                points = componentPoints
            )
        }

        return total
    }

    // -------------------------------------------------------------------------
    // 5. Threat intel
    // -------------------------------------------------------------------------

    private fun scoreThreatIntel(
        snapshot: AppSnapshot,
        reasons: MutableList<Reason>,
        raiseFloor: (RiskBand?, String) -> Unit
    ): Double = when (val verdict = snapshot.hashVerdict) {
        is HashVerdict.Malicious -> {
            raiseFloor(rubric.threatIntel.maliciousBandFloor, "THREAT_INTEL_MALICIOUS")
            reasons += Reason(
                code = "THREAT_INTEL_MALICIOUS",
                severity = Severity.CRITICAL,
                headline = "A security service recognises this app as harmful",
                detail = "The fingerprint of this app's installer file matches something already reported as malicious. Only the fingerprint was checked online, never the app itself or anything about you.",
                evidence = listOf("provider=${verdict.provider}", "detections=${verdict.detections}"),
                points = rubric.threatIntel.malicious
            )
            rubric.threatIntel.malicious.toDouble()
        }
        is HashVerdict.Suspicious -> {
            reasons += Reason(
                code = "THREAT_INTEL_SUSPICIOUS",
                severity = Severity.HIGH,
                headline = "Some security services have flagged this app",
                detail = "A small number of services consider this app's installer file suspicious. That is weaker evidence than a clear detection, but worth knowing.",
                evidence = listOf("provider=${verdict.provider}", "detections=${verdict.detections}"),
                points = rubric.threatIntel.suspicious
            )
            rubric.threatIntel.suspicious.toDouble()
        }
        else -> 0.0
    }

    // -------------------------------------------------------------------------
    // 6. Combinations
    // -------------------------------------------------------------------------

    private fun scoreCombinations(
        snapshot: AppSnapshot,
        reasons: MutableList<Reason>,
        raiseFloor: (RiskBand?, String) -> Unit
    ): Double {
        val facts = CombinationFacts.from(snapshot, trust)

        val fired = CombinationRules.predicates
            .filter { (_, predicate) -> predicate(facts) }
            .keys

        // Drop a weaker rule when the stronger one covering the same ground fired.
        val effective = fired.filter { id ->
            CombinationRules.supersededBy[id]?.let { it !in fired } ?: true
        }

        var total = 0.0
        effective.forEach { id ->
            val config = rubric.combinationRules[id] ?: return@forEach
            total += config.bonus
            raiseFloor(config.bandFloor, id)
            reasons += Reason(
                code = id,
                severity = config.severity,
                headline = config.headline,
                detail = config.detail,
                evidence = listOf("combination_rule=$id"),
                points = config.bonus
            )
        }

        return total
    }

    // -------------------------------------------------------------------------
    // 7. Context mismatch
    // -------------------------------------------------------------------------

    private fun scoreContext(
        context: ContextAnalysis,
        reasons: MutableList<Reason>,
        raiseFloor: (RiskBand?, String) -> Unit
    ): Double {
        if (!context.hasMismatch) return 0.0
        var total = 0.0

        // Only the worst mismatch contributes points. Several mismatches on one app
        // describe the same underlying oddity ("this is not what it says it is")
        // and adding them all up would double-count a single observation.
        val worst = context.worst ?: return 0.0
        val bonus = rubric.contextMismatchBonus[worst.severity] ?: 0
        if (bonus == 0) return 0.0

        total += bonus
        raiseFloor(rubric.contextMismatchBandFloor[worst.severity], "CONTEXT_MISMATCH")

        reasons += Reason(
            code = "CONTEXT_MISMATCH_${worst.category.name}",
            severity = worst.severity,
            headline = worst.headline(),
            detail = "An app's access should match what it is for. This one asks for something its description gives no reason to need.",
            evidence = buildList {
                add("archetype=${worst.archetype.name}")
                worst.matchedOn?.let { add("matched_on=$it") }
                addAll(worst.grantedPermissions)
            },
            points = bonus
        )

        // Any further mismatches are reported for the user without adding points.
        context.mismatches.filter { it !== worst }.forEach { extra ->
            reasons += Reason(
                code = "CONTEXT_MISMATCH_${extra.category.name}",
                severity = Severity.INFO,
                headline = extra.headline(),
                detail = "Listed for completeness. It has not been scored again, because it is part of the same mismatch already counted above.",
                evidence = extra.grantedPermissions,
                points = 0
            )
        }

        return total
    }

    // -------------------------------------------------------------------------
    // Reason padding
    // -------------------------------------------------------------------------

    /**
     * PS-01 must-have 05 requires at least three plain-language reasons for every
     * High or Critical app.
     *
     * Padding only ever promotes reasons that describe something **true about this
     * app** and that the engine already computed but filtered out for being
     * low-value. Nothing is invented. If there genuinely are not enough true
     * things to say, the list comes back short rather than padded with fiction —
     * and `RiskEngineTest` treats that as a failure to investigate, not a case to
     * paper over.
     */
    private fun padReasons(
        existing: List<Reason>,
        snapshot: AppSnapshot,
        target: Int
    ): List<Reason> {
        if (existing.size >= target) return existing

        val have = existing.map { it.code }.toSet()
        val candidates = mutableListOf<Reason>()

        // Tier 1: declared-but-not-granted sensitive access the app has asked for.
        snapshot.permissions
            .filter { it.isLatent && catalog.sensitivityOf(it.name) >= 0.5 }
            .groupBy { it.category }
            .forEach { (category, records) ->
                val code = "LATENT_${category.name}"
                if (code in have) return@forEach
                candidates += Reason(
                    code = code,
                    severity = Severity.LOW,
                    headline = CategoryPhrases.latentHeadline(category),
                    detail = "It has asked for this and does not have it yet. If you are ever prompted, this is what you would be agreeing to.",
                    evidence = records.map { it.name }.sorted(),
                    points = 0
                )
            }

        // Tier 2: special access that is declared but not switched on.
        snapshot.specialAccess.declaredOnlyKinds.forEach { kind ->
            val code = "SPECIAL_DECLARED_${kind.name}"
            if (code in have) return@forEach
            candidates += Reason(
                code = code,
                severity = Severity.LOW,
                headline = "It has asked for " + CategoryPhrases.specialAccessHeadline(kind)
                    .removePrefix("It ").replaceFirstChar { it.lowercase() },
                detail = CategoryPhrases.specialAccessDetail(kind) + CategoryPhrases.declaredOnlySuffix(),
                evidence = listOf("special_access=${kind.name}", "state=declared_only"),
                points = 0
            )
        }

        // Tier 3: plain factual provenance statements.
        if ("FACT_INTERNET" !in have &&
            snapshot.permissions.any { it.name == "android.permission.INTERNET" && it.declared }
        ) {
            candidates += Reason(
                code = "FACT_INTERNET",
                severity = Severity.INFO,
                headline = "It can send data off your phone",
                detail = "On its own this is ordinary. It matters because it is the route anything this app collects would take to leave your device.",
                evidence = listOf("android.permission.INTERNET"),
                points = 0
            )
        }
        if ("FACT_INSTALL_SOURCE" !in have) {
            candidates += Reason(
                code = "FACT_INSTALL_SOURCE",
                severity = Severity.INFO,
                headline = "It was installed from " +
                    com.riskauditor.trust.InstallerClassifier.displayName(snapshot.app.installer.kind),
                detail = "Where an app came from is part of how much you can trust it.",
                evidence = listOfNotNull(snapshot.app.installer.installingPackage),
                points = 0
            )
        }

        return (existing + candidates.take(target - existing.size))
    }

    // -------------------------------------------------------------------------

    private fun severityForCategoryPoints(points: Int): Severity = when {
        points >= 20 -> Severity.CRITICAL
        points >= 12 -> Severity.HIGH
        points >= 5 -> Severity.MEDIUM
        points > 0 -> Severity.LOW
        else -> Severity.INFO
    }

    private fun pow(base: Double, exponent: Int): Double {
        var result = 1.0
        repeat(max(exponent, 0)) { result *= base }
        return result
    }
}
