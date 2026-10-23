package com.riskauditor.scoring

import com.riskauditor.model.Category
import com.riskauditor.model.Protection
import com.riskauditor.model.RiskBand
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Wire types for rubric.json.
 *
 * These exist so that [Rubric] and everything under it stay free of serialization
 * annotations and can be built directly in tests and in the offline verification
 * harness. The cost is this boilerplate; the benefit is that the engine's own
 * types have no dependency on how they happen to be stored.
 */

@Serializable
private data class RubricDto(
    @SerialName("version") val version: Int,
    @SerialName("bands") val bands: BandsDto,
    @SerialName("category_weights") val categoryWeights: Map<Category, CategoryWeightDto>,
    @SerialName("protection_multipliers") val protectionMultipliers: Map<Protection, Double>,
    @SerialName("state_multipliers") val stateMultipliers: StateMultipliersDto,
    @SerialName("special_access_weights") val specialAccessWeights: Map<SpecialAccessKind, SpecialWeightDto>,
    @SerialName("caps") val caps: CapsDto,
    @SerialName("provenance") val provenance: ProvenanceDto,
    @SerialName("reducers") val reducers: ReducersDto,
    @SerialName("manifest") val manifest: ManifestDto,
    @SerialName("threat_intel") val threatIntel: ThreatIntelDto,
    @SerialName("context_mismatch_bonus") val contextMismatchBonus: Map<Severity, Int>,
    @SerialName("context_mismatch_band_floor") val contextMismatchBandFloor: Map<Severity, RiskBand?>,
    @SerialName("minimum_reasons_for_high_or_critical") val minimumReasons: Int,
    @SerialName("combination_rules") val combinationRules: Map<String, CombinationRuleDto>
)

@Serializable
private data class BandsDto(
    @SerialName("medium_from") val mediumFrom: Int,
    @SerialName("high_from") val highFrom: Int,
    @SerialName("critical_from") val criticalFrom: Int
)

@Serializable
private data class CategoryWeightDto(
    @SerialName("base") val base: Double,
    @SerialName("cap") val cap: Double,
    @SerialName("diminishing") val diminishing: Double
)

@Serializable
private data class StateMultipliersDto(
    @SerialName("granted") val granted: Double,
    @SerialName("declared_not_granted") val declaredNotGranted: Double,
    @SerialName("not_grantable") val notGrantable: Double
)

@Serializable
private data class SpecialWeightDto(
    @SerialName("enabled") val enabled: Double,
    @SerialName("declared_only") val declaredOnly: Double
)

@Serializable
private data class CapsDto(
    @SerialName("permission_subtotal") val permissionSubtotal: Double,
    @SerialName("special_access_subtotal") val specialAccessSubtotal: Double,
    @SerialName("network_category") val networkCategory: Double
)

@Serializable
private data class ProvenanceDto(
    @SerialName("sideloaded") val sideloaded: Int,
    @SerialName("adb_or_none") val adbOrNone: Int,
    @SerialName("unknown_installer") val unknownInstaller: Int,
    @SerialName("google_play") val googlePlay: Int,
    @SerialName("oem_store") val oemStore: Int,
    @SerialName("target_sdk_below_23") val targetSdkBelow23: Int,
    @SerialName("no_launcher_icon") val noLauncherIcon: Int,
    @SerialName("installed_within_hours") val installedWithinHours: Int,
    @SerialName("recent_install_window_hours") val recentInstallWindowHours: Int
)

@Serializable
private data class ReducersDto(
    @SerialName("verified_security_tool_device_admin") val verifiedSecurityToolDeviceAdmin: Double,
    @SerialName("verified_accessibility_tool") val verifiedAccessibilityTool: Double,
    @SerialName("role_holder_category") val roleHolderCategory: Double,
    @SerialName("system_app_dampening") val systemAppDampening: Double
)

@Serializable
private data class ManifestDto(
    @SerialName("debuggable") val debuggable: Int,
    @SerialName("cleartext_traffic") val cleartextTraffic: Int,
    @SerialName("test_only") val testOnly: Int,
    @SerialName("unguarded_exported_provider") val unguardedExportedProvider: Int,
    @SerialName("unguarded_exported_component") val unguardedExportedComponent: Int,
    @SerialName("max_unguarded_component_points") val maxUnguardedComponentPoints: Int
)

@Serializable
private data class ThreatIntelDto(
    @SerialName("malicious") val malicious: Int,
    @SerialName("suspicious") val suspicious: Int,
    @SerialName("malicious_band_floor") val maliciousBandFloor: RiskBand
)

@Serializable
private data class CombinationRuleDto(
    @SerialName("bonus") val bonus: Int,
    @SerialName("band_floor") val bandFloor: RiskBand? = null,
    @SerialName("severity") val severity: Severity,
    @SerialName("headline") val headline: String,
    @SerialName("detail") val detail: String
)

/** Reads `rubric.json` into a [Rubric]. */
object RubricLoader {

    private val json = Json { ignoreUnknownKeys = true }

    const val RESOURCE_PATH = "/rubric.json"

    fun fromJson(text: String): Rubric {
        val dto = json.decodeFromString<RubricDto>(text)
        return Rubric(
            version = dto.version,
            bands = BandThresholds(dto.bands.mediumFrom, dto.bands.highFrom, dto.bands.criticalFrom),
            categoryWeights = dto.categoryWeights.mapValues {
                CategoryWeight(it.value.base, it.value.cap, it.value.diminishing)
            },
            protectionMultipliers = dto.protectionMultipliers,
            stateMultipliers = StateMultipliers(
                dto.stateMultipliers.granted,
                dto.stateMultipliers.declaredNotGranted,
                dto.stateMultipliers.notGrantable
            ),
            specialAccessWeights = dto.specialAccessWeights.mapValues {
                SpecialAccessWeight(it.value.enabled, it.value.declaredOnly)
            },
            caps = Caps(
                dto.caps.permissionSubtotal,
                dto.caps.specialAccessSubtotal,
                dto.caps.networkCategory
            ),
            provenance = ProvenanceModifiers(
                sideloaded = dto.provenance.sideloaded,
                adbOrNone = dto.provenance.adbOrNone,
                unknownInstaller = dto.provenance.unknownInstaller,
                googlePlay = dto.provenance.googlePlay,
                oemStore = dto.provenance.oemStore,
                targetSdkBelow23 = dto.provenance.targetSdkBelow23,
                noLauncherIcon = dto.provenance.noLauncherIcon,
                installedWithinHours = dto.provenance.installedWithinHours,
                recentInstallWindowHours = dto.provenance.recentInstallWindowHours
            ),
            reducers = Reducers(
                dto.reducers.verifiedSecurityToolDeviceAdmin,
                dto.reducers.verifiedAccessibilityTool,
                dto.reducers.roleHolderCategory,
                dto.reducers.systemAppDampening
            ),
            manifest = ManifestModifiers(
                debuggable = dto.manifest.debuggable,
                cleartextTraffic = dto.manifest.cleartextTraffic,
                testOnly = dto.manifest.testOnly,
                unguardedExportedProvider = dto.manifest.unguardedExportedProvider,
                unguardedExportedComponent = dto.manifest.unguardedExportedComponent,
                maxUnguardedComponentPoints = dto.manifest.maxUnguardedComponentPoints
            ),
            threatIntel = ThreatIntelModifiers(
                dto.threatIntel.malicious,
                dto.threatIntel.suspicious,
                dto.threatIntel.maliciousBandFloor
            ),
            contextMismatchBonus = dto.contextMismatchBonus,
            contextMismatchBandFloor = dto.contextMismatchBandFloor,
            combinationRules = dto.combinationRules.mapValues { (id, c) ->
                CombinationRuleConfig(id, c.bonus, c.bandFloor, c.severity, c.headline, c.detail)
            },
            minimumReasonsForHighOrCritical = dto.minimumReasons
        )
    }

    fun load(): Rubric {
        val stream = RubricLoader::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("rubric.json missing from the classpath at $RESOURCE_PATH")
        return stream.bufferedReader().use { fromJson(it.readText()) }
    }

    val default: Rubric by lazy { load() }
}
