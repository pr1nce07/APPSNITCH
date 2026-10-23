/* Offline replacement for RubricLoader. See HarnessLoaders.kt for why this exists. */

package com.riskauditor.scoring

import com.riskauditor.model.Category
import com.riskauditor.model.Protection
import com.riskauditor.model.RiskBand
import com.riskauditor.model.Severity
import com.riskauditor.model.SpecialAccessKind
import verify.JsonValue
import verify.MiniJson
import verify.int
import verify.isNull
import verify.num
import verify.obj
import verify.opt
import verify.req
import verify.str

private inline fun <reified E : Enum<E>> bySerialName(key: String): E =
    enumValues<E>().firstOrNull { it.name.lowercase() == key.lowercase() }
        ?: error("no ${E::class.simpleName} matches serial name '$key'")

object RubricLoader {

    const val RESOURCE_PATH = "/rubric.json"

    fun fromJson(text: String): Rubric {
        val root = MiniJson.parse(text).obj()

        val bandsNode = root.req("bands").obj()
        val bands = BandThresholds(
            mediumFrom = bandsNode.req("medium_from").int(),
            highFrom = bandsNode.req("high_from").int(),
            criticalFrom = bandsNode.req("critical_from").int()
        )

        val categoryWeights = root.req("category_weights").obj().entries.associate { (key, node) ->
            val w = node.obj()
            bySerialName<Category>(key) to CategoryWeight(
                base = w.req("base").num(),
                cap = w.req("cap").num(),
                diminishing = w.req("diminishing").num()
            )
        }

        val protectionMultipliers = root.req("protection_multipliers").obj().entries
            .associate { (key, node) -> bySerialName<Protection>(key) to node.num() }

        val sm = root.req("state_multipliers").obj()
        val stateMultipliers = StateMultipliers(
            granted = sm.req("granted").num(),
            declaredNotGranted = sm.req("declared_not_granted").num(),
            notGrantable = sm.req("not_grantable").num()
        )

        val specialWeights = root.req("special_access_weights").obj().entries.associate { (key, node) ->
            val w = node.obj()
            bySerialName<SpecialAccessKind>(key) to SpecialAccessWeight(
                enabled = w.req("enabled").num(),
                declaredOnly = w.req("declared_only").num()
            )
        }

        val capsNode = root.req("caps").obj()
        val caps = Caps(
            permissionSubtotal = capsNode.req("permission_subtotal").num(),
            specialAccessSubtotal = capsNode.req("special_access_subtotal").num(),
            networkCategory = capsNode.req("network_category").num()
        )

        val p = root.req("provenance").obj()
        val provenance = ProvenanceModifiers(
            sideloaded = p.req("sideloaded").int(),
            adbOrNone = p.req("adb_or_none").int(),
            unknownInstaller = p.req("unknown_installer").int(),
            googlePlay = p.req("google_play").int(),
            oemStore = p.req("oem_store").int(),
            targetSdkBelow23 = p.req("target_sdk_below_23").int(),
            noLauncherIcon = p.req("no_launcher_icon").int(),
            installedWithinHours = p.req("installed_within_hours").int(),
            recentInstallWindowHours = p.req("recent_install_window_hours").int()
        )

        val r = root.req("reducers").obj()
        val reducers = Reducers(
            verifiedSecurityToolDeviceAdmin = r.req("verified_security_tool_device_admin").num(),
            verifiedAccessibilityTool = r.req("verified_accessibility_tool").num(),
            roleHolderCategory = r.req("role_holder_category").num(),
            systemAppDampening = r.req("system_app_dampening").num()
        )

        val m = root.req("manifest").obj()
        val manifest = ManifestModifiers(
            debuggable = m.req("debuggable").int(),
            cleartextTraffic = m.req("cleartext_traffic").int(),
            testOnly = m.req("test_only").int(),
            unguardedExportedProvider = m.req("unguarded_exported_provider").int(),
            unguardedExportedComponent = m.req("unguarded_exported_component").int(),
            maxUnguardedComponentPoints = m.req("max_unguarded_component_points").int()
        )

        val t = root.req("threat_intel").obj()
        val threatIntel = ThreatIntelModifiers(
            malicious = t.req("malicious").int(),
            suspicious = t.req("suspicious").int(),
            maliciousBandFloor = bySerialName<RiskBand>(t.req("malicious_band_floor").str())
        )

        val contextBonus = root.req("context_mismatch_bonus").obj().entries
            .associate { (key, node) -> bySerialName<Severity>(key) to node.int() }

        val contextFloor: Map<Severity, RiskBand?> =
            root.req("context_mismatch_band_floor").obj().entries.associate { (key, node) ->
                bySerialName<Severity>(key) to if (node.isNull()) null else bySerialName<RiskBand>(node.str())
            }

        val combos = root.req("combination_rules").obj().entries.associate { (id, node) ->
            val c = node.obj()
            id to CombinationRuleConfig(
                id = id,
                bonus = c.req("bonus").int(),
                bandFloor = (c["band_floor"] as? JsonValue)
                    ?.takeUnless { it.isNull() }
                    ?.let { bySerialName<RiskBand>(it.str()) },
                severity = bySerialName<Severity>(c.req("severity").str()),
                headline = c.req("headline").str(),
                detail = c.req("detail").str()
            )
        }

        return Rubric(
            version = root.req("version").int(),
            bands = bands,
            categoryWeights = categoryWeights,
            protectionMultipliers = protectionMultipliers,
            stateMultipliers = stateMultipliers,
            specialAccessWeights = specialWeights,
            caps = caps,
            provenance = provenance,
            reducers = reducers,
            manifest = manifest,
            threatIntel = threatIntel,
            contextMismatchBonus = contextBonus,
            contextMismatchBandFloor = contextFloor,
            combinationRules = combos,
            minimumReasonsForHighOrCritical = root.req("minimum_reasons_for_high_or_critical").int()
        )
    }

    fun load(): Rubric {
        val stream = RubricLoader::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("rubric.json missing from the classpath at $RESOURCE_PATH")
        return stream.bufferedReader().use { fromJson(it.readText()) }
    }

    val default: Rubric by lazy { load() }
}
