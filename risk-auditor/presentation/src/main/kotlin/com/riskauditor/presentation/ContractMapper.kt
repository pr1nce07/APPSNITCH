package com.riskauditor.presentation

import com.riskauditor.model.AccessState
import com.riskauditor.model.AppRecord
import com.riskauditor.model.Category
import com.riskauditor.model.DeviceScan
import com.riskauditor.model.InstallerKind
import com.riskauditor.model.Reason
import com.riskauditor.model.RiskBand
import com.riskauditor.model.ScoredApp
import com.riskauditor.model.SpecialAccessKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset

/**
 * Converts a [DeviceScan] into the AppSnitch ScanResult JSON defined in
 * appsnitch/docs/DATA-CONTRACT.md (schemaVersion 1).
 *
 * Pure Kotlin, no Android imports, so it can be unit-tested on the JVM.
 *
 * Decisions, all deliberate:
 *  - Scores come from the engine. The UI derives the band from the score, so a
 *    band floor applied by a rule would be lost. When the engine band is above
 *    what the score alone implies, the score is raised to that band's floor so
 *    the UI shows the same band the engine chose.
 *  - Permission categories the contract does not know (accounts/sensors, uncategorized)
 *    are left out of `access`; the contract ignores unknown keys anyway.
 *  - Icons are not sent (the engine does not produce them); the UI falls back to letters.
 */
object ContractMapper {

    const val SCHEMA_VERSION = 1

    /** Same thresholds as risk-auditor scoring/src/main/resources/rubric.json "bands". */
    private const val MEDIUM_FROM = 30
    private const val HIGH_FROM = 60
    private const val CRITICAL_FROM = 80

    /** Engine category -> contract `access` key. Special access is handled separately. */
    private val categoryKey: Map<Category, String> = mapOf(
        Category.SMS to "texts",
        Category.CONTACTS_AND_CALL_LOGS to "contacts",
        Category.CAMERA_AND_MICROPHONE to "camera",
        Category.LOCATION to "location",
        Category.PHONE_AND_IDENTITY to "phone",
        Category.STORAGE_AND_MEDIA to "files",
        Category.BACKGROUND_AND_PERSISTENCE to "background",
        Category.NETWORK to "network"
    )

    fun toScanResult(
        scan: DeviceScan,
        reportBuilder: ReportBuilder,
        deviceModel: String?,
        androidVersion: String?
    ): JsonObject {
        val seen = HashSet<String>()
        val apps = scan.apps
            .filter { seen.add(it.packageName) } // duplicates are skipped by the UI anyway; keep it clean
            .map { toApp(it, reportBuilder) }

        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("scannedAt", Instant.ofEpochMilli(scan.scannedAt).toString())
            if (deviceModel != null || androidVersion != null) {
                put("device", buildJsonObject {
                    if (deviceModel != null) put("model", deviceModel)
                    if (androidVersion != null) put("androidVersion", androidVersion)
                })
            }
            put("apps", JsonArray(apps))
        }
    }

    private fun toApp(scored: ScoredApp, reportBuilder: ReportBuilder): JsonObject {
        val app = scored.snapshot.app
        // A failure building the detail view must not drop the app; it just loses its fixes.
        val detail: AppDetail? = runCatching { reportBuilder.appDetail(scored) }.getOrNull()
        val installed = installedDate(app.firstInstallTime)

        return buildJsonObject {
            put("packageName", app.packageName)
            put("id", app.packageName)
            put("name", app.label.ifBlank { app.packageName })
            if (app.versionName != null) put("versionName", app.versionName!!)
            put("installedAt", if (installed != null) JsonPrimitive(installed) else JsonNull)
            put("installSource", installSource(app))
            put("isSystem", app.isSystemOrUpdatedSystem)
            put("targetsOldAndroid", app.predatesRuntimePermissions)
            if (app.predatesRuntimePermissions) {
                put("oldAndroidLabel", "Built for Android 5.1 or older (target SDK ${app.targetSdk})")
            }
            put("score", scoreWithBandFloor(scored.risk.score, scored.risk.band))
            put("reasons", JsonArray(scored.risk.reasons.map { reasonJson(it) }))
            put("access", accessJson(detail))
            put("fixes", JsonArray(fixesJson(detail)))
        }
    }

    /** The UI derives the band from the score, so the score must be at least the engine band's floor. */
    private fun scoreWithBandFloor(score: Int, band: RiskBand): Int {
        val floor = when (band) {
            RiskBand.CRITICAL -> CRITICAL_FROM
            RiskBand.HIGH -> HIGH_FROM
            RiskBand.MEDIUM -> MEDIUM_FROM
            RiskBand.LOW -> 0
        }
        return maxOf(score, floor).coerceIn(0, 100)
    }

    private fun installSource(app: AppRecord): String = when {
        app.isSystem -> "system"
        app.installer.kind == InstallerKind.GOOGLE_PLAY -> "play_store"
        app.installer.kind == InstallerKind.SIDELOADED ||
            app.installer.kind == InstallerKind.ADB_OR_NONE -> "sideloaded"
        else -> "other" // OEM store, other store, unknown installer
    }

    private fun installedDate(ms: Long): String? =
        if (ms <= 0L) null else Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** Engine reason codes: CATEGORY_*, SPECIAL_*, COMBO_*, CONTEXT_MISMATCH_*, everything else. */
    private fun reasonJson(reason: Reason): JsonObject {
        val (kind, category) = classify(reason.code)
        return buildJsonObject {
            put("kind", kind)
            if (category != null) put("category", category)
            put("latent", isLatent(reason))
            put("title", reason.headline)
            put("detail", reason.detail)
            put("points", reason.points)
        }
    }

    private fun classify(code: String): Pair<String, String?> = when {
        code.startsWith("CATEGORY_") -> {
            val key = categoryKeyFor(code.removePrefix("CATEGORY_"))
            if (key != null) "access" to key else "trust" to null
        }
        code.startsWith("SPECIAL_") -> "access" to "special"
        code.startsWith("CONTEXT_MISMATCH") -> "context" to null
        code.startsWith("COMBO_") -> "combo" to null
        else -> "trust" to null
    }

    private fun categoryKeyFor(enumName: String): String? =
        Category.entries.firstOrNull { it.name == enumName }?.let { categoryKey[it] }

    /** A category reason is latent when every permission behind it is declared but not granted. */
    private fun isLatent(reason: Reason): Boolean =
        reason.code.startsWith("CATEGORY_") &&
            reason.evidence.isNotEmpty() &&
            reason.evidence.all { "(declared," in it }

    private fun accessJson(detail: AppDetail?): JsonObject {
        val grouped = LinkedHashMap<String, MutableList<JsonElement>>()

        detail?.categoryGroups?.forEach { group ->
            val key = categoryKey[group.category] ?: return@forEach
            val items = grouped.getOrPut(key) { mutableListOf() }
            group.permissions.forEach { p ->
                items += buildJsonObject {
                    put("label", p.plainText)
                    put("permission", p.name)
                    put("granted", p.granted)
                    put("protection", protectionKey(p.protection))
                }
            }
        }

        detail?.specialAccess?.forEach { row ->
            if (row.state == AccessState.NOT_PRESENT) return@forEach
            val items = grouped.getOrPut("special") { mutableListOf() }
            items += buildJsonObject {
                put("label", row.displayName)
                put("permission", row.evidence ?: row.kind.name.lowercase())
                put("granted", row.state == AccessState.ENABLED)
                put("protection", "special")
                if (row.kind == SpecialAccessKind.ACCESSIBILITY) put("screenReader", true)
            }
        }

        return buildJsonObject {
            grouped.forEach { (key, items) -> put(key, JsonArray(items)) }
        }
    }

    /** The contract accepts dangerous | normal | special only. */
    private fun protectionKey(engineValue: String): String = when (engineValue) {
        "dangerous" -> "dangerous"
        "special" -> "special"
        else -> "normal"
    }

    private fun fixesJson(detail: AppDetail?): List<JsonElement> =
        detail?.actions.orEmpty().sortedBy { it.order }.map { a ->
            buildJsonObject {
                val text = if (a.explanation.isBlank()) a.title else "${a.title.trimEnd('.')}. ${a.explanation}"
                put("text", text)
                put(
                    "action",
                    when (a.kind) {
                        ActionKind.UNINSTALL -> "uninstall"
                        ActionKind.DISABLE_SYSTEM_APP -> "info"
                        else -> "settings"
                    }
                )
            }
        }
}
