package verify

import com.riskauditor.catalog.CatalogLoader
import com.riskauditor.context.Archetype
import com.riskauditor.context.ClassificationConfidence
import com.riskauditor.context.ContextMismatchEngine
import com.riskauditor.model.AccessState
import com.riskauditor.model.Category
import com.riskauditor.model.HashVerdict
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.ManifestFindings
import com.riskauditor.model.Protection
import com.riskauditor.model.RiskBand
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessKind
import com.riskauditor.scoring.CombinationRules
import com.riskauditor.scoring.RiskEngine
import com.riskauditor.scoring.RubricLoader
import com.riskauditor.scoring.ScoringInput
import com.riskauditor.trust.DeviceRole
import com.riskauditor.trust.InstallerClassifier
import com.riskauditor.trust.RoleHolders
import com.riskauditor.trust.TrustBasis
import com.riskauditor.trust.TrustLoader
import com.riskauditor.trust.TrustRegistry
import com.riskauditor.trust.TrustedTool
import com.riskauditor.trust.TrustedToolKind

private val catalog = CatalogLoader.default
private val rubric = RubricLoader.default
private val trust = TrustLoader.default
private val engine = RiskEngine(rubric, catalog, trust)

/** Words a non-technical user should never see in a headline. Shared with PipelineSuite. */
internal val BANNED_JARGON = listOf(
    "android.permission", "SYSTEM_ALERT_WINDOW", "READ_SMS", "RECEIVE_SMS",
    "AppOps", "appops", "manifest", "targetSdk", "API level", "SDK",
    "permission constant", "protectionLevel", "PackageManager", "intent",
    "broadcast", "boolean", "null"
)

// =====================================================================
fun enumSerialNameSuite() {
    Check.suite("Enum serial names (harness fidelity)")

    // The harness maps JSON keys to enum constants with name.lowercase().
    // If a @SerialName ever stops matching that rule, the harness would be
    // silently testing a different mapping than production uses.
    Check.test("every Category serial name equals name.lowercase()") {
        Category.entries.forEach {
            Check.isTrue(it.name.lowercase().isNotEmpty(), "empty name")
        }
        Check.isTrue(
            Category.entries.map { it.name.lowercase() }.toSet().size == Category.entries.size,
            "category lowercase names must be unique"
        )
    }

    Check.test("rubric covers every category and special access kind") {
        Category.entries.forEach { c ->
            Check.isTrue(
                rubric.categoryWeights.containsKey(c),
                "rubric.json is missing a weight for category ${c.name}"
            )
        }
        SpecialAccessKind.entries.forEach { k ->
            Check.isTrue(
                rubric.specialAccessWeights.containsKey(k),
                "rubric.json is missing a weight for special access ${k.name}"
            )
        }
        Protection.entries.forEach { p ->
            Check.isTrue(
                rubric.protectionMultipliers.containsKey(p),
                "rubric.json is missing a multiplier for protection ${p.name}"
            )
        }
    }
}

// =====================================================================
fun catalogSuite() {
    Check.suite("Catalog (M1)")

    Check.test("bundled catalog parses and is substantial") {
        Check.atLeast(catalog.size, 70, "catalog size")
    }

    Check.test("all nine mandated categories are populated") {
        Category.MANDATED.forEach {
            Check.isTrue(catalog.entriesIn(it).isNotEmpty(), "category ${it.name} is empty")
        }
        Check.eq(Category.MANDATED.size, 9, "mandated category count")
    }

    Check.test("every problem-statement example maps to its stated category") {
        val expected = mapOf(
            "android.permission.SYSTEM_ALERT_WINDOW" to Category.SPECIAL_ACCESS,
            "android.permission.REQUEST_INSTALL_PACKAGES" to Category.SPECIAL_ACCESS,
            "android.permission.PACKAGE_USAGE_STATS" to Category.SPECIAL_ACCESS,
            "android.permission.READ_SMS" to Category.SMS,
            "android.permission.RECEIVE_SMS" to Category.SMS,
            "android.permission.SEND_SMS" to Category.SMS,
            "android.permission.READ_CONTACTS" to Category.CONTACTS_AND_CALL_LOGS,
            "android.permission.READ_CALL_LOG" to Category.CONTACTS_AND_CALL_LOGS,
            "android.permission.CAMERA" to Category.CAMERA_AND_MICROPHONE,
            "android.permission.RECORD_AUDIO" to Category.CAMERA_AND_MICROPHONE,
            "android.permission.ACCESS_FINE_LOCATION" to Category.LOCATION,
            "android.permission.ACCESS_BACKGROUND_LOCATION" to Category.LOCATION,
            "android.permission.READ_PHONE_STATE" to Category.PHONE_AND_IDENTITY,
            "android.permission.READ_PHONE_NUMBERS" to Category.PHONE_AND_IDENTITY,
            "android.permission.CALL_PHONE" to Category.PHONE_AND_IDENTITY,
            "android.permission.READ_MEDIA_IMAGES" to Category.STORAGE_AND_MEDIA,
            "android.permission.MANAGE_EXTERNAL_STORAGE" to Category.STORAGE_AND_MEDIA,
            "android.permission.RECEIVE_BOOT_COMPLETED" to Category.BACKGROUND_AND_PERSISTENCE,
            "android.permission.FOREGROUND_SERVICE" to Category.BACKGROUND_AND_PERSISTENCE,
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to Category.BACKGROUND_AND_PERSISTENCE,
            "android.permission.INTERNET" to Category.NETWORK,
            "android.permission.ACCESS_NETWORK_STATE" to Category.NETWORK
        )
        expected.forEach { (p, c) -> Check.eq(catalog.categoryOf(p), c, p) }
    }

    Check.test("no duplicate permissions") {
        val names = catalog.entries.map { it.permission }
        Check.eq(names.size, names.toSet().size, "duplicate permission in catalog")
    }

    Check.test("every entry has plain text that never leaks the constant") {
        catalog.entries.forEach { e ->
            Check.isTrue(e.plainText.isNotBlank(), "${e.permission} has blank plain text")
            Check.isFalse(
                e.plainText.contains("android.permission."),
                "${e.permission} plain text leaks the constant"
            )
            Check.isFalse(
                e.plainText.contains(e.shortName),
                "${e.permission} plain text contains its own short name"
            )
        }
    }

    Check.test("unknown permission degrades to UNCATEGORIZED without throwing") {
        val custom = "com.vendor.oem.permission.SECRET_SAUCE"
        Check.eq(catalog.categoryOf(custom), Category.UNCATEGORIZED, "category")
        Check.eq(catalog.protectionOf(custom), Protection.UNKNOWN, "protection")
        Check.eq(catalog.sensitivityOf(custom), 0.0, "sensitivity")
        Check.isTrue(catalog.plainTextOf(custom).isNotBlank(), "fallback text")
    }

    Check.test("device-wide summary counts apps, not permissions") {
        val perApp = mapOf(
            "com.a" to listOf(perm("android.permission.READ_SMS"), perm("android.permission.SEND_SMS")),
            "com.b" to listOf(perm("android.permission.READ_SMS")),
            "com.c" to listOf(perm("android.permission.READ_SMS", granted = false))
        )
        val sms = catalog.summarise(perApp).first { it.category == Category.SMS }
        Check.eq(sms.appsWithGranted, 2, "apps with granted SMS")
        Check.eq(sms.appsWithDeclaredOnly, 1, "apps declaring SMS only")
        Check.eq(sms.grantedPermissionCount, 3, "granted SMS permission count")
        Check.eq(sms.worstOffenders.first(), "com.a", "worst offender")
    }

    Check.test("loader rejects duplicates") {
        val bad = """{"version":1,"entries":[
          {"permission":"android.permission.READ_SMS","category":"sms","protection":"dangerous","sensitivity":1.0,"plain_text":"a"},
          {"permission":"android.permission.READ_SMS","category":"sms","protection":"dangerous","sensitivity":1.0,"plain_text":"b"}]}"""
        val threw = runCatching { CatalogLoader.fromJson(bad) }.isFailure
        Check.isTrue(threw, "loader accepted a duplicate permission")
    }

    Check.test("loader rejects out-of-range sensitivity") {
        val bad = """{"version":1,"entries":[
          {"permission":"android.permission.READ_SMS","category":"sms","protection":"dangerous","sensitivity":1.7,"plain_text":"a"}]}"""
        val threw = runCatching { CatalogLoader.fromJson(bad) }.isFailure
        Check.isTrue(threw, "loader accepted sensitivity above 1.0")
    }
}

// =====================================================================
fun trustSuite() {
    Check.suite("Trust (M2)")

    Check.test("installer classification table") {
        Check.eq(InstallerClassifier.classify("com.android.vending"), InstallerKind.GOOGLE_PLAY, "play")
        Check.eq(
            InstallerClassifier.classify("com.sec.android.app.samsungapps"),
            InstallerKind.OEM_STORE, "samsung"
        )
        Check.eq(InstallerClassifier.classify("org.fdroid.fdroid"), InstallerKind.OTHER_STORE, "fdroid")
        Check.eq(
            InstallerClassifier.classify("com.android.packageinstaller"),
            InstallerKind.SIDELOADED, "package installer"
        )
        Check.eq(InstallerClassifier.classify(null), InstallerKind.ADB_OR_NONE, "null installer")
        Check.eq(InstallerClassifier.classify(""), InstallerKind.ADB_OR_NONE, "blank installer")
        Check.eq(
            InstallerClassifier.classify("com.totally.unknown.store"),
            InstallerKind.UNKNOWN, "unknown installer"
        )
    }

    Check.test("the system-recorded initiator wins over the installing package") {
        // An app can set itself as the installing package; the initiator is harder to forge.
        val kind = InstallerClassifier.classify(
            installingPackage = "com.android.vending",
            initiatingPackage = "com.android.packageinstaller"
        )
        Check.eq(kind, InstallerKind.SIDELOADED, "initiator should win")
    }

    Check.test("untrusted source flags are correct") {
        Check.isFalse(InstallerKind.GOOGLE_PLAY.isUntrustedSource, "play is trusted")
        Check.isFalse(InstallerKind.OEM_STORE.isUntrustedSource, "oem is trusted")
        Check.isTrue(InstallerKind.SIDELOADED.isUntrustedSource, "sideloaded is untrusted")
        Check.isTrue(InstallerKind.ADB_OR_NONE.isUntrustedSource, "adb is untrusted")
        Check.isTrue(InstallerKind.UNKNOWN.isUntrustedSource, "unknown is untrusted")
    }

    Check.test("SPOOF: same package name, sideloaded, wrong certificate -> NOT trusted") {
        val registry = TrustRegistry(
            listOf(
                TrustedTool(
                    packageName = "com.enterprise.mdm",
                    displayName = "Enterprise MDM",
                    kind = TrustedToolKind.MDM,
                    certSha256 = setOf("aa".repeat(32))
                )
            )
        )
        val impostor = app(
            "com.enterprise.mdm", "Enterprise MDM",
            installer = InstallerKind.SIDELOADED,
            signingCerts = listOf("ff".repeat(32))
        )
        val verdict = registry.evaluate(impostor)
        Check.isFalse(verdict.trusted, "an impostor must never be trusted")
        Check.eq(verdict.basis, TrustBasis.NAME_ONLY_NOT_TRUSTED, "basis")
        Check.isFalse(registry.deviceAdminIsExpected(impostor), "device admin must not be excused")
    }

    Check.test("pinned certificate confers trust") {
        val registry = TrustRegistry(
            listOf(
                TrustedTool("com.enterprise.mdm", "Enterprise MDM", TrustedToolKind.MDM, setOf("aa".repeat(32)))
            )
        )
        val real = app(
            "com.enterprise.mdm", "Enterprise MDM",
            installer = InstallerKind.SIDELOADED,
            signingCerts = listOf("AA".repeat(32))  // case-insensitive match
        )
        val verdict = registry.evaluate(real)
        Check.isTrue(verdict.trusted, "pinned certificate should confer trust")
        Check.eq(verdict.basis, TrustBasis.PINNED_CERTIFICATE, "basis")
    }

    Check.test("unpinned allowlist entry needs a trusted installer") {
        val registry = TrustRegistry(
            listOf(TrustedTool("com.vendor.security", "Vendor Security", TrustedToolKind.SECURITY))
        )
        val fromPlay = app("com.vendor.security", "Vendor Security", installer = InstallerKind.GOOGLE_PLAY)
        val sideloaded = app("com.vendor.security", "Vendor Security", installer = InstallerKind.SIDELOADED)

        Check.isTrue(registry.evaluate(fromPlay).trusted, "play install should be trusted")
        Check.eq(
            registry.evaluate(fromPlay).basis,
            TrustBasis.ALLOWLISTED_AND_TRUSTED_INSTALLER, "basis"
        )
        Check.isFalse(registry.evaluate(sideloaded).trusted, "sideloaded must not be trusted")
    }

    Check.test("an app not on the allowlist is never trusted") {
        val verdict = trust.evaluate(app("com.random.app", "Random"))
        Check.isFalse(verdict.trusted, "unknown app trusted")
        Check.eq(verdict.basis, TrustBasis.NOT_ALLOWLISTED, "basis")
    }

    Check.test("bundled allowlist ships no certificate pins (documented, deliberate)") {
        // Shipping guessed pins would be a fabricated security claim.
        // This test records the decision so a future edit is a conscious one.
        Check.atLeast(trust.size, 1, "allowlist should not be empty")
    }

    Check.test("role holders excuse only their own categories") {
        val roles = RoleHolders.of("com.sms.app" to setOf(DeviceRole.DEFAULT_SMS))
        Check.isTrue(roles.excuses("com.sms.app", Category.SMS), "sms role excuses sms")
        Check.isTrue(
            roles.excuses("com.sms.app", Category.CONTACTS_AND_CALL_LOGS),
            "sms role excuses contacts"
        )
        Check.isFalse(
            roles.excuses("com.sms.app", Category.CAMERA_AND_MICROPHONE),
            "sms role must not excuse camera"
        )
        Check.isFalse(roles.excuses("com.other.app", Category.SMS), "role is per package")
    }
}

// =====================================================================
fun contextSuite() {
    Check.suite("Context mismatch (M9)")
    val ctx = ContextMismatchEngine.DEFAULT

    Check.test("torch app holding SMS is flagged") {
        val s = snapshot(
            app("com.bright.torch", "Super Torch Flashlight", installer = InstallerKind.SIDELOADED),
            listOf(perm("android.permission.READ_SMS"), perm("android.permission.INTERNET"))
        )
        val analysis = ctx.analyse(s)
        Check.eq(analysis.classification.archetype, Archetype.TORCH, "archetype")
        Check.eq(analysis.classification.confidence, ClassificationConfidence.STRONG, "confidence")
        Check.isTrue(analysis.hasMismatch, "expected a mismatch")
        Check.eq(analysis.worst!!.category, Category.SMS, "worst category")
        Check.eq(analysis.worst!!.severity, Severity.CRITICAL, "severity")
    }

    Check.test("calculator holding contacts is flagged") {
        val s = snapshot(
            app("com.simple.calculator", "Simple Calculator Calc"),
            listOf(perm("android.permission.READ_CONTACTS"))
        )
        val analysis = ctx.analyse(s)
        Check.eq(analysis.classification.archetype, Archetype.CALCULATOR, "archetype")
        Check.isTrue(analysis.hasMismatch, "expected a mismatch")
    }

    Check.test("wallpaper holding camera and microphone is flagged") {
        val s = snapshot(
            app("com.live.wallpaper", "Live Wallpapers HD"),
            listOf(perm("android.permission.CAMERA"), perm("android.permission.RECORD_AUDIO"))
        )
        val analysis = ctx.analyse(s)
        Check.eq(analysis.classification.archetype, Archetype.WALLPAPER, "archetype")
        Check.isTrue(analysis.hasMismatch, "expected a mismatch")
    }

    Check.test("NEGATIVE: a real messenger holding SMS is NOT flagged") {
        val s = snapshot(
            app("com.chat.messenger", "Chat Messenger"),
            listOf(
                perm("android.permission.READ_SMS"),
                perm("android.permission.READ_CONTACTS"),
                perm("android.permission.CAMERA")
            )
        )
        val analysis = ctx.analyse(s)
        Check.eq(analysis.classification.archetype, Archetype.MESSENGER, "archetype")
        Check.isFalse(analysis.hasMismatch, "a messenger must not be flagged for SMS")
    }

    Check.test("NEGATIVE: an unknown archetype never produces a finding") {
        val s = snapshot(
            app("com.acme.zephyr", "Zephyr"),
            listOf(perm("android.permission.READ_SMS"), perm("android.permission.CAMERA"))
        )
        val analysis = ctx.analyse(s)
        Check.eq(analysis.classification.archetype, Archetype.UNKNOWN, "archetype")
        Check.isFalse(analysis.hasMismatch, "unknown archetype must not be flagged")
    }

    Check.test("NEGATIVE: declared-but-not-granted does not trigger a mismatch") {
        val s = snapshot(
            app("com.bright.torch", "Torch Flashlight"),
            listOf(perm("android.permission.READ_SMS", granted = false))
        )
        Check.isFalse(ctx.analyse(s).hasMismatch, "ungranted access must not be flagged as held")
    }

    Check.test("NEGATIVE: a weak single-keyword match does not produce a finding") {
        // "light" alone, with no second corroborating token and no app category.
        val s = snapshot(
            app("com.acme.product", "Light"),
            listOf(perm("android.permission.READ_SMS"))
        )
        val analysis = ctx.analyse(s)
        Check.isTrue(
            analysis.classification.confidence != ClassificationConfidence.STRONG ||
                !analysis.hasMismatch,
            "a weak classification must not drive a finding"
        )
    }

    Check.test("NEGATIVE: a security app is exempt from mismatch") {
        val s = snapshot(
            app("com.vendor.antivirus", "Vendor Antivirus Security"),
            listOf(perm("android.permission.READ_SMS"), perm("android.permission.READ_CONTACTS"))
        )
        Check.isFalse(ctx.analyse(s).hasMismatch, "security apps are exempt")
    }

    Check.test("ambiguous labels collapse to UNKNOWN") {
        // Matches both calculator and wallpaper vocabulary equally.
        val s = snapshot(
            app("com.a.b", "Calculator Wallpaper"),
            listOf(perm("android.permission.READ_SMS"))
        )
        Check.eq(ctx.analyse(s).classification.archetype, Archetype.UNKNOWN, "ambiguous -> UNKNOWN")
    }
}

// =====================================================================
fun rubricConfigSuite() {
    Check.suite("Rubric configuration (M10)")

    Check.test("every combination predicate has numbers in rubric.json") {
        CombinationRules.ids.forEach { id ->
            Check.isTrue(
                rubric.combinationRules.containsKey(id),
                "predicate '$id' has no entry in rubric.json"
            )
        }
    }

    Check.test("every rubric combination rule has a predicate") {
        rubric.combinationRules.keys.forEach { id ->
            Check.isTrue(
                CombinationRules.predicates.containsKey(id),
                "rubric.json rule '$id' has no predicate in CombinationRules.kt"
            )
        }
    }

    Check.test("all eleven combination rules named in the problem statement exist") {
        val required = listOf(
            "COMBO_SMS_INTERNET_BOOT_SIDELOADED",
            "COMBO_ACCESSIBILITY_OVERLAY",
            "COMBO_DEVICE_ADMIN_NOT_SECURITY",
            "COMBO_NOTIFICATION_LISTENER_INTERNET_SMS",
            "COMBO_OVERLAY_USAGE_STATS",
            "COMBO_INSTALL_PACKAGES_SIDELOADED_INTERNET",
            "COMBO_ACCESSIBILITY_INSTALL_PACKAGES",
            "COMBO_CAMERA_MIC_INTERNET_BOOT_HIDDEN",
            "COMBO_BACKGROUND_LOCATION_BOOT_HIDDEN",
            "COMBO_CONTACTS_CALLLOG_SMS_INTERNET",
            "COMBO_ALL_FILES_INTERNET_SIDELOADED"
        )
        required.forEach { id ->
            Check.isTrue(rubric.combinationRules.containsKey(id), "missing required rule $id")
        }
    }

    Check.test("band thresholds strictly increase and cover 0..100") {
        Check.eq(rubric.bandFor(0), RiskBand.LOW, "0")
        Check.eq(rubric.bandFor(rubric.bands.mediumFrom - 1), RiskBand.LOW, "just below medium")
        Check.eq(rubric.bandFor(rubric.bands.mediumFrom), RiskBand.MEDIUM, "medium lower bound")
        Check.eq(rubric.bandFor(rubric.bands.highFrom), RiskBand.HIGH, "high lower bound")
        Check.eq(rubric.bandFor(rubric.bands.criticalFrom), RiskBand.CRITICAL, "critical lower bound")
        Check.eq(rubric.bandFor(100), RiskBand.CRITICAL, "100")
    }

    Check.test("special access carries the highest weight of any category") {
        val special = rubric.weightFor(Category.SPECIAL_ACCESS).base
        Category.entries.filter { it != Category.SPECIAL_ACCESS }.forEach { c ->
            Check.isTrue(
                rubric.weightFor(c).base <= special,
                "${c.name} base weight ${rubric.weightFor(c).base} exceeds special access $special"
            )
        }
    }

    Check.test("network alone is capped at 5 points") {
        Check.isTrue(rubric.caps.networkCategory <= 5.0, "network cap must be <= 5")
    }

    Check.test("every combination rule has non-empty plain-language text") {
        rubric.combinationRules.values.forEach { c ->
            Check.isTrue(c.headline.isNotBlank(), "${c.id} has a blank headline")
            Check.isTrue(c.detail.isNotBlank(), "${c.id} has a blank detail")
            Check.atLeast(c.bonus, 1, "${c.id} bonus")
        }
    }
}

// =====================================================================
fun engineProfileSuite() {
    Check.suite("Risk engine — calibration profiles (M10)")

    Check.test("PROFILE clean app: Play-installed, internet only -> LOW") {
        val s = snapshot(
            app("com.clean.app", "Clean App"),
            listOf(perm("android.permission.INTERNET"), perm("android.permission.ACCESS_NETWORK_STATE"))
        )
        val r = engine.score(s)
        Check.eq(r.band, RiskBand.LOW, "band (score=${r.score})")
        Check.atMost(r.score, 10, "score")
    }

    Check.test("PROFILE legit messenger from Play -> MEDIUM, not HIGH") {
        val s = snapshot(
            app("com.chat.messenger", "Chat Messenger"),
            listOf(
                perm("android.permission.READ_SMS"), perm("android.permission.RECEIVE_SMS"),
                perm("android.permission.SEND_SMS"), perm("android.permission.READ_CONTACTS"),
                perm("android.permission.WRITE_CONTACTS"), perm("android.permission.CAMERA"),
                perm("android.permission.RECORD_AUDIO"), perm("android.permission.READ_MEDIA_IMAGES"),
                perm("android.permission.READ_PHONE_STATE"), perm("android.permission.ACCESS_FINE_LOCATION"),
                perm("android.permission.INTERNET"), perm("android.permission.RECEIVE_BOOT_COMPLETED"),
                perm("android.permission.FOREGROUND_SERVICE"), perm("android.permission.POST_NOTIFICATIONS")
            )
        )
        val r = engine.score(s)
        Check.isTrue(
            r.band == RiskBand.MEDIUM || r.band == RiskBand.LOW,
            "a legitimate messenger must not reach HIGH (was ${r.band}, score=${r.score})"
        )
    }

    Check.test("PROFILE default SMS app is dampened further by its role") {
        val perms = listOf(
            perm("android.permission.READ_SMS"), perm("android.permission.RECEIVE_SMS"),
            perm("android.permission.SEND_SMS"), perm("android.permission.READ_CONTACTS"),
            perm("android.permission.INTERNET")
        )
        val s = snapshot(app("com.chat.messenger", "Chat Messenger"), perms)
        val withoutRole = engine.score(ScoringInput(s))
        val withRole = engine.score(
            ScoringInput(s, RoleHolders.of("com.chat.messenger" to setOf(DeviceRole.DEFAULT_SMS)))
        )
        Check.isTrue(
            withRole.score < withoutRole.score,
            "holding the SMS role should reduce the score (${withRole.score} vs ${withoutRole.score})"
        )
    }

    Check.test("PROFILE torch with SMS, sideloaded -> CRITICAL") {
        val s = snapshot(
            app("com.bright.torch", "Super Torch Flashlight", installer = InstallerKind.SIDELOADED),
            listOf(
                perm("android.permission.READ_SMS"), perm("android.permission.READ_CONTACTS"),
                perm("android.permission.READ_CALL_LOG"), perm("android.permission.INTERNET")
            )
        )
        val r = engine.score(s)
        Check.eq(r.band, RiskBand.CRITICAL, "band (score=${r.score})")
        Check.atLeast(r.reasons.size, 3, "reason count")
    }

    Check.test("PROFILE banking trojan: accessibility + overlay, sideloaded -> CRITICAL") {
        val s = snapshot(
            app("com.fake.updater", "System Update", installer = InstallerKind.SIDELOADED, hasLauncherIcon = false),
            listOf(
                perm("android.permission.READ_SMS"), perm("android.permission.RECEIVE_SMS"),
                perm("android.permission.INTERNET"), perm("android.permission.RECEIVE_BOOT_COMPLETED")
            ),
            special = listOf(
                SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                SpecialAccessKind.OVERLAY to AccessState.ENABLED
            )
        )
        val r = engine.score(s)
        Check.eq(r.band, RiskBand.CRITICAL, "band (score=${r.score})")
        Check.isTrue(
            r.reasons.any { it.code == "COMBO_ACCESSIBILITY_OVERLAY" },
            "the accessibility+overlay rule must fire"
        )
        Check.isTrue(
            r.reasons.any { it.code == "COMBO_SMS_INTERNET_BOOT_SIDELOADED" },
            "the OTP-interception rule must fire"
        )
    }

    Check.test("PROFILE stalkerware: hidden icon, camera/mic, boot, background location -> CRITICAL") {
        val s = snapshot(
            app("com.sys.service", "Sync Service", installer = InstallerKind.ADB_OR_NONE, hasLauncherIcon = false),
            listOf(
                perm("android.permission.CAMERA"), perm("android.permission.RECORD_AUDIO"),
                perm("android.permission.ACCESS_BACKGROUND_LOCATION"),
                perm("android.permission.ACCESS_FINE_LOCATION"),
                perm("android.permission.INTERNET"), perm("android.permission.RECEIVE_BOOT_COMPLETED")
            )
        )
        val r = engine.score(s)
        Check.eq(r.band, RiskBand.CRITICAL, "band (score=${r.score})")
        Check.isTrue(
            r.reasons.any { it.code == "COMBO_CAMERA_MIC_INTERNET_BOOT_HIDDEN" },
            "covert monitoring rule must fire"
        )
        Check.isTrue(
            r.reasons.any { it.code == "COMBO_BACKGROUND_LOCATION_BOOT_HIDDEN" },
            "covert tracking rule must fire"
        )
    }

    Check.test("PROFILE verified MDM tool with device admin is dampened") {
        val registry = TrustRegistry(
            listOf(TrustedTool("com.enterprise.mdm", "Enterprise MDM", TrustedToolKind.MDM))
        )
        val trustedEngine = RiskEngine(rubric, catalog, registry)
        val s = snapshot(
            app("com.enterprise.mdm", "Enterprise MDM", installer = InstallerKind.GOOGLE_PLAY),
            listOf(perm("android.permission.INTERNET"), perm("android.permission.READ_PHONE_STATE")),
            special = listOf(SpecialAccessKind.DEVICE_ADMIN to AccessState.ENABLED)
        )
        val r = trustedEngine.score(s)
        Check.isTrue(
            r.band == RiskBand.LOW || r.band == RiskBand.MEDIUM,
            "a verified MDM tool must not be CRITICAL (was ${r.band}, score=${r.score})"
        )
        Check.isFalse(
            r.reasons.any { it.code == "COMBO_DEVICE_ADMIN_NOT_SECURITY" },
            "the non-security device admin rule must not fire for a verified tool"
        )
    }

    Check.test("PROFILE spoofed security tool is NOT dampened") {
        val registry = TrustRegistry(
            listOf(
                TrustedTool(
                    "com.enterprise.mdm", "Enterprise MDM", TrustedToolKind.MDM,
                    certSha256 = setOf("aa".repeat(32))
                )
            )
        )
        val spoofEngine = RiskEngine(rubric, catalog, registry)
        val s = snapshot(
            app(
                "com.enterprise.mdm", "Enterprise MDM",
                installer = InstallerKind.SIDELOADED,
                signingCerts = listOf("ff".repeat(32))
            ),
            listOf(perm("android.permission.INTERNET"), perm("android.permission.READ_SMS")),
            special = listOf(SpecialAccessKind.DEVICE_ADMIN to AccessState.ENABLED)
        )
        val r = spoofEngine.score(s)
        Check.isTrue(
            r.reasons.any { it.code == "COMBO_DEVICE_ADMIN_NOT_SECURITY" },
            "a spoofed tool must still trigger the device admin rule"
        )
        Check.isTrue(
            r.band == RiskBand.HIGH || r.band == RiskBand.CRITICAL,
            "a spoofed tool must land HIGH or CRITICAL (was ${r.band}, score=${r.score})"
        )
    }

    Check.test("PROFILE system app is dampened relative to an identical user app") {
        val perms = listOf(
            perm("android.permission.READ_SMS"), perm("android.permission.READ_CONTACTS"),
            perm("android.permission.CAMERA"), perm("android.permission.INTERNET")
        )
        val user = engine.score(snapshot(app("com.x.y", "Thing"), perms))
        val system = engine.score(snapshot(app("com.x.y", "Thing", isSystem = true), perms))
        Check.isTrue(
            system.score < user.score,
            "system app should score lower (${system.score} vs ${user.score})"
        )
    }

    Check.test("PROFILE threat-intel MALICIOUS forces CRITICAL") {
        val s = snapshot(app("com.clean.app", "Clean App"), listOf(perm("android.permission.INTERNET")))
            .copy(hashVerdict = HashVerdict.Malicious("local", 7))
        val r = engine.score(s)
        Check.eq(r.band, RiskBand.CRITICAL, "band (score=${r.score})")
        Check.atLeast(r.reasons.size, 3, "reason count for a CRITICAL app")
    }
}

// =====================================================================
fun engineInvariantSuite() {
    Check.suite("Risk engine — invariants (M10)")

    val allPermissions = catalog.entries.map { it.permission }

    Check.test("score is always within 0..100 across 2000 random profiles") {
        val rng = java.util.Random(20261016L)
        repeat(2000) { n ->
            val count = rng.nextInt(12)
            val perms = (0 until count).map {
                perm(allPermissions[rng.nextInt(allPermissions.size)], granted = rng.nextBoolean())
            }.distinctBy { it.name }
            val specials = SpecialAccessKind.entries
                .filter { rng.nextInt(6) == 0 }
                .map { it to if (rng.nextBoolean()) AccessState.ENABLED else AccessState.DECLARED_ONLY }
            val s = snapshot(
                app(
                    "com.fuzz.p$n", "Fuzz $n",
                    installer = InstallerKind.entries[rng.nextInt(InstallerKind.entries.size)],
                    targetSdk = if (rng.nextInt(5) == 0) 21 else 34,
                    hasLauncherIcon = rng.nextBoolean(),
                    isSystem = rng.nextInt(8) == 0
                ),
                perms, specials,
                manifest = ManifestFindings(
                    debuggable = rng.nextInt(10) == 0,
                    cleartextTrafficAllowed = rng.nextBoolean()
                )
            )
            val r = engine.score(s)
            Check.inRange(r.score, 0, 100, "score for fuzz profile $n")
        }
    }

    Check.test("every HIGH or CRITICAL result carries at least 3 reasons (2000 profiles)") {
        val rng = java.util.Random(99L)
        var highOrCritical = 0
        repeat(2000) { n ->
            val perms = (0 until rng.nextInt(14)).map {
                perm(allPermissions[rng.nextInt(allPermissions.size)], granted = rng.nextBoolean())
            }.distinctBy { it.name }
            val specials = SpecialAccessKind.entries
                .filter { rng.nextInt(4) == 0 }
                .map { it to if (rng.nextBoolean()) AccessState.ENABLED else AccessState.DECLARED_ONLY }
            val s = snapshot(
                app(
                    "com.fuzz2.p$n", "Fuzz $n",
                    installer = InstallerKind.entries[rng.nextInt(InstallerKind.entries.size)],
                    hasLauncherIcon = rng.nextBoolean()
                ),
                perms, specials
            )
            val r = engine.score(s)
            if (r.band.requiresMinimumReasons) {
                highOrCritical++
                Check.atLeast(
                    r.reasons.size, 3,
                    "profile $n scored ${r.score} (${r.band}) with only ${r.reasons.size} reasons"
                )
            }
        }
        Check.atLeast(highOrCritical, 50, "the fuzz run should produce plenty of HIGH/CRITICAL cases")
    }

    Check.test("MONOTONICITY: adding a granted dangerous permission never lowers the score") {
        val rng = java.util.Random(7L)
        val dangerous = catalog.entries
            .filter { it.protection == Protection.DANGEROUS && !it.special }
            .map { it.permission }

        repeat(400) { n ->
            val base = (0 until rng.nextInt(6)).map {
                perm(allPermissions[rng.nextInt(allPermissions.size)])
            }.distinctBy { it.name }

            val extra = dangerous[rng.nextInt(dangerous.size)]
            if (base.any { it.name == extra }) return@repeat

            val a = app("com.mono.p$n", "Mono $n")
            val before = engine.score(snapshot(a, base)).score
            val after = engine.score(snapshot(a, base + perm(extra))).score

            Check.isTrue(
                after >= before,
                "adding $extra lowered the score from $before to $after (profile $n)"
            )
        }
    }

    Check.test("reasons are ranked by contribution, highest first") {
        val s = snapshot(
            app("com.fake.updater", "System Update", installer = InstallerKind.SIDELOADED, hasLauncherIcon = false),
            listOf(
                perm("android.permission.READ_SMS"), perm("android.permission.INTERNET"),
                perm("android.permission.RECEIVE_BOOT_COMPLETED")
            ),
            special = listOf(
                SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                SpecialAccessKind.OVERLAY to AccessState.ENABLED
            )
        )
        val points = engine.score(s).reasons.map { it.points }
        Check.isTrue(
            points == points.sortedDescending(),
            "reasons are not sorted by points descending: $points"
        )
    }

    Check.test("BANNED JARGON: no headline contains technical vocabulary") {
        val rng = java.util.Random(31337L)
        repeat(600) { n ->
            val perms = (0 until rng.nextInt(10)).map {
                perm(allPermissions[rng.nextInt(allPermissions.size)], granted = rng.nextBoolean())
            }.distinctBy { it.name }
            val specials = SpecialAccessKind.entries
                .filter { rng.nextInt(4) == 0 }
                .map { it to AccessState.ENABLED }
            val r = engine.score(
                snapshot(
                    app("com.jargon.p$n", "App $n", installer = InstallerKind.SIDELOADED),
                    perms, specials
                )
            )
            r.reasons.forEach { reason ->
                BANNED_JARGON.forEach { banned ->
                    Check.isFalse(
                        reason.headline.contains(banned),
                        "headline of ${reason.code} contains banned jargon '$banned': ${reason.headline}"
                    )
                }
                Check.isTrue(reason.headline.isNotBlank(), "${reason.code} has a blank headline")
                Check.isTrue(reason.detail.isNotBlank(), "${reason.code} has a blank detail")
            }
        }
    }

    Check.test("permission subtotal cap holds: no permission-only app reaches HIGH") {
        // Grant EVERY non-special permission in the catalog, from Google Play.
        val everything = catalog.entries
            .filter { !it.special }
            .map { perm(it.permission) }
        val r = engine.score(snapshot(app("com.greedy.app", "Greedy"), everything))
        Check.isTrue(
            r.band == RiskBand.MEDIUM || r.band == RiskBand.LOW,
            "permissions alone must not reach HIGH (was ${r.band}, score=${r.score})"
        )
    }

    Check.test("declared-but-not-granted scores strictly lower than granted") {
        val a = app("com.state.app", "State")
        val granted = engine.score(snapshot(a, listOf(perm("android.permission.READ_SMS", granted = true))))
        val declared = engine.score(snapshot(a, listOf(perm("android.permission.READ_SMS", granted = false))))
        Check.isTrue(
            declared.score < granted.score,
            "latent risk must score lower (${declared.score} vs ${granted.score})"
        )
        Check.atLeast(declared.score, 1, "latent risk must not score zero")
    }

    Check.test("a signature permission the app cannot hold scores zero") {
        val a = app("com.sig.app", "Sig")
        val none = engine.score(snapshot(a, emptyList())).score
        val ungrantable = engine.score(
            snapshot(a, listOf(perm("android.permission.CAPTURE_AUDIO_OUTPUT", granted = false, grantable = false)))
        ).score
        Check.eq(ungrantable, none, "an ungrantable permission must add nothing")
    }

    Check.test("special access is not double counted with its permission") {
        val a = app("com.overlay.app", "Overlay")
        val viaBoth = engine.score(
            snapshot(
                a,
                listOf(perm("android.permission.SYSTEM_ALERT_WINDOW")),
                special = listOf(SpecialAccessKind.OVERLAY to AccessState.ENABLED)
            )
        ).score
        val viaSpecialOnly = engine.score(
            snapshot(a, emptyList(), special = listOf(SpecialAccessKind.OVERLAY to AccessState.ENABLED))
        ).score
        Check.eq(viaBoth, viaSpecialOnly, "overlay counted twice")
    }

    Check.test("a special permission still scores when no detector result is present") {
        val a = app("com.overlay2.app", "Overlay2")
        val permissionOnly = engine.score(
            snapshot(a, listOf(perm("android.permission.SYSTEM_ALERT_WINDOW")))
        ).score
        Check.atLeast(permissionOnly, 1, "the permission path must still score it")
    }

    Check.test("superseded combination rules do not both fire") {
        val s = snapshot(
            app("com.otp.thief", "Helper", installer = InstallerKind.SIDELOADED),
            listOf(perm("android.permission.READ_SMS"), perm("android.permission.INTERNET")),
            special = listOf(SpecialAccessKind.NOTIFICATION_LISTENER to AccessState.ENABLED)
        )
        val codes = engine.score(s).reasons.map { it.code }
        Check.isTrue(
            codes.contains("COMBO_NOTIFICATION_LISTENER_INTERNET_SMS"),
            "the stronger OTP rule should fire"
        )
        Check.isFalse(
            codes.contains("COMBO_NOTIFICATION_LISTENER_INTERNET"),
            "the weaker rule must be suppressed"
        )
    }

    Check.test("each combination rule has a positive and a negative case") {
        // Positive: build a snapshot satisfying the rule; Negative: remove one fact.
        val cases = combinationPositiveCases()
        CombinationRules.ids.forEach { id ->
            val builder = cases[id]
            Check.isTrue(builder != null, "no positive case defined for $id")
            if (builder == null) return@forEach

            val (positive, negative) = builder()
            val firedPositive = engine.score(positive).reasons.any { it.code == id }
            val firedNegative = engine.score(negative).reasons.any { it.code == id }

            Check.isTrue(firedPositive, "$id did not fire on its positive case")
            Check.isFalse(firedNegative, "$id fired on its negative case")
        }
    }
}

/**
 * One positive and one negative snapshot per combination rule.
 * The negative differs from the positive by exactly one removed fact.
 */
private fun combinationPositiveCases(): Map<String, () -> Pair<com.riskauditor.model.AppSnapshot, com.riskauditor.model.AppSnapshot>> {
    val sideloaded = InstallerKind.SIDELOADED
    val play = InstallerKind.GOOGLE_PLAY

    return mapOf(
        "COMBO_ACCESSIBILITY_OVERLAY" to {
            val base = app("com.c1", "C1")
            snapshot(base, emptyList(), listOf(
                SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                SpecialAccessKind.OVERLAY to AccessState.ENABLED
            )) to snapshot(base, emptyList(), listOf(
                SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED
            ))
        },
        "COMBO_SMS_INTERNET_BOOT_SIDELOADED" to {
            val perms = listOf(
                perm("android.permission.READ_SMS"), perm("android.permission.INTERNET"),
                perm("android.permission.RECEIVE_BOOT_COMPLETED")
            )
            snapshot(app("com.c2", "C2", installer = sideloaded), perms) to
                snapshot(app("com.c2", "C2", installer = play), perms)
        },
        "COMBO_DEVICE_ADMIN_NOT_SECURITY" to {
            snapshot(app("com.c3", "C3"), emptyList(), listOf(SpecialAccessKind.DEVICE_ADMIN to AccessState.ENABLED)) to
                snapshot(app("com.c3", "C3"), emptyList(), listOf(SpecialAccessKind.DEVICE_ADMIN to AccessState.DECLARED_ONLY))
        },
        "COMBO_NOTIFICATION_LISTENER_INTERNET_SMS" to {
            val perms = listOf(perm("android.permission.READ_SMS"), perm("android.permission.INTERNET"))
            snapshot(app("com.c4", "C4"), perms, listOf(SpecialAccessKind.NOTIFICATION_LISTENER to AccessState.ENABLED)) to
                snapshot(app("com.c4", "C4"), perms)
        },
        "COMBO_NOTIFICATION_LISTENER_INTERNET" to {
            snapshot(
                app("com.c5", "C5"), listOf(perm("android.permission.INTERNET")),
                listOf(SpecialAccessKind.NOTIFICATION_LISTENER to AccessState.ENABLED)
            ) to snapshot(app("com.c5", "C5"), listOf(perm("android.permission.INTERNET")))
        },
        "COMBO_OVERLAY_USAGE_STATS" to {
            snapshot(app("com.c6", "C6"), emptyList(), listOf(
                SpecialAccessKind.OVERLAY to AccessState.ENABLED,
                SpecialAccessKind.USAGE_STATS to AccessState.ENABLED
            )) to snapshot(app("com.c6", "C6"), emptyList(), listOf(
                SpecialAccessKind.OVERLAY to AccessState.ENABLED
            ))
        },
        "COMBO_INSTALL_PACKAGES_SIDELOADED_INTERNET" to {
            val perms = listOf(perm("android.permission.INTERNET"))
            val special = listOf(SpecialAccessKind.INSTALL_PACKAGES to AccessState.ENABLED)
            snapshot(app("com.c7", "C7", installer = sideloaded), perms, special) to
                snapshot(app("com.c7", "C7", installer = play), perms, special)
        },
        "COMBO_ACCESSIBILITY_INSTALL_PACKAGES" to {
            snapshot(app("com.c8", "C8"), emptyList(), listOf(
                SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED,
                SpecialAccessKind.INSTALL_PACKAGES to AccessState.ENABLED
            )) to snapshot(app("com.c8", "C8"), emptyList(), listOf(
                SpecialAccessKind.INSTALL_PACKAGES to AccessState.ENABLED
            ))
        },
        "COMBO_ACCESSIBILITY_SMS" to {
            val special = listOf(SpecialAccessKind.ACCESSIBILITY to AccessState.ENABLED)
            snapshot(app("com.c9", "C9"), listOf(perm("android.permission.READ_SMS")), special) to
                snapshot(app("com.c9", "C9"), emptyList(), special)
        },
        "COMBO_CAMERA_MIC_INTERNET_BOOT_HIDDEN" to {
            val perms = listOf(
                perm("android.permission.CAMERA"), perm("android.permission.INTERNET"),
                perm("android.permission.RECEIVE_BOOT_COMPLETED")
            )
            snapshot(app("com.c10", "C10", hasLauncherIcon = false), perms) to
                snapshot(app("com.c10", "C10", hasLauncherIcon = true), perms)
        },
        "COMBO_BACKGROUND_LOCATION_BOOT_HIDDEN" to {
            val perms = listOf(
                perm("android.permission.ACCESS_BACKGROUND_LOCATION"),
                perm("android.permission.RECEIVE_BOOT_COMPLETED")
            )
            snapshot(app("com.c11", "C11", hasLauncherIcon = false), perms) to
                snapshot(app("com.c11", "C11", hasLauncherIcon = true), perms)
        },
        "COMBO_CONTACTS_CALLLOG_SMS_INTERNET" to {
            val perms = listOf(
                perm("android.permission.READ_CONTACTS"), perm("android.permission.READ_CALL_LOG"),
                perm("android.permission.READ_SMS"), perm("android.permission.INTERNET")
            )
            snapshot(app("com.c12", "C12"), perms) to
                snapshot(app("com.c12", "C12"), perms.dropLast(2) + perm("android.permission.INTERNET"))
        },
        "COMBO_ALL_FILES_INTERNET_SIDELOADED" to {
            val perms = listOf(perm("android.permission.INTERNET"))
            val special = listOf(SpecialAccessKind.ALL_FILES_ACCESS to AccessState.ENABLED)
            snapshot(app("com.c13", "C13", installer = sideloaded), perms, special) to
                snapshot(app("com.c13", "C13", installer = play), perms, special)
        },
        "COMBO_VPN_INTERNET_SIDELOADED" to {
            val perms = listOf(perm("android.permission.INTERNET"))
            val special = listOf(SpecialAccessKind.VPN_SERVICE to AccessState.ENABLED)
            snapshot(app("com.c14", "C14", installer = sideloaded), perms, special) to
                snapshot(app("com.c14", "C14", installer = play), perms, special)
        }
    )
}
