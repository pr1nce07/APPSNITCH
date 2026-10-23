package com.riskauditor.catalog

import com.riskauditor.model.Category
import com.riskauditor.model.PermissionRecord
import com.riskauditor.model.Protection

/**
 * One permission the catalog knows about.
 *
 * @param permission fully-qualified permission name, e.g. `android.permission.READ_SMS`.
 * @param category which of the ten buckets it belongs to.
 * @param protection the protection level AOSP declares for it. This is a **fallback**:
 *        the protection level read from the device at scan time always wins, because
 *        OEMs do redefine permissions. See `docs/ASSUMPTIONS.md` A-04.
 * @param sensitivity 0.0..1.0 — how much damage this single permission enables.
 *        Used by the rubric as a per-permission multiplier inside its category.
 * @param plainText what this lets an app do, in words a non-technical user reads.
 *        Must never contain the permission constant itself.
 * @param minApi first API level the permission exists at (0 = since forever).
 * @param maxApi last API level it applies to, or null if current.
 * @param special true for access granted through a dedicated Settings screen or an
 *        AppOps flag rather than a runtime dialog.
 */
data class CatalogEntry(
    val permission: String,
    val category: Category,
    val protection: Protection,
    val sensitivity: Double,
    val plainText: String,
    val minApi: Int = 0,
    val maxApi: Int? = null,
    val special: Boolean = false
) {
    init {
        require(sensitivity in 0.0..1.0) {
            "sensitivity for $permission must be within 0.0..1.0, was $sensitivity"
        }
        require(plainText.isNotBlank()) { "plainText for $permission must not be blank" }
    }

    val shortName: String get() = permission.substringAfterLast('.')
}

/**
 * Device-wide aggregation for one category: the "14 apps can read your SMS" line.
 */
data class CategorySummary(
    val category: Category,
    /** Apps holding at least one *granted* permission in this category. */
    val appsWithGranted: Int,
    /** Apps that declare but have not been granted anything here. */
    val appsWithDeclaredOnly: Int,
    /** Total granted permissions across all apps in this category. */
    val grantedPermissionCount: Int,
    /** Package names of the apps contributing most to this category, strongest first. */
    val worstOffenders: List<String>
) {
    val isEmpty: Boolean get() = appsWithGranted == 0 && appsWithDeclaredOnly == 0
}

/**
 * Immutable lookup from permission name to category and metadata.
 *
 * Construct it from the bundled data file via [CatalogLoader]. Taking the entry
 * list in the constructor (rather than reading the file here) is what keeps this
 * class free of any I/O or serialization dependency, so it can be exercised
 * directly from tests and from the offline verification harness.
 */
class PermissionCatalog(entries: List<CatalogEntry>) {

    private val byName: Map<String, CatalogEntry> = entries.associateBy { it.permission }

    init {
        val duplicates = entries.groupingBy { it.permission }.eachCount().filterValues { it > 1 }
        require(duplicates.isEmpty()) {
            "duplicate permissions in catalog: ${duplicates.keys.sorted()}"
        }
    }

    val entries: List<CatalogEntry> = entries.sortedBy { it.permission }

    val size: Int get() = byName.size

    /** Null when the permission is custom, vendor-defined or simply not in the catalog. */
    fun find(permission: String): CatalogEntry? = byName[permission]

    /**
     * Never throws and never guesses: an unknown permission is
     * [Category.UNCATEGORIZED], which the rubric scores at zero.
     */
    fun categoryOf(permission: String): Category =
        byName[permission]?.category ?: Category.UNCATEGORIZED

    fun protectionOf(permission: String): Protection =
        byName[permission]?.protection ?: Protection.UNKNOWN

    fun sensitivityOf(permission: String): Double = byName[permission]?.sensitivity ?: 0.0

    /** The user-facing sentence, or a safe generic one for permissions we do not know. */
    fun plainTextOf(permission: String): String =
        byName[permission]?.plainText
            ?: "This is a custom permission defined by the app or your phone's maker. " +
            "We cannot tell what it allows."

    fun entriesIn(category: Category): List<CatalogEntry> =
        entries.filter { it.category == category }

    fun isSpecial(permission: String): Boolean = byName[permission]?.special == true

    /** Every permission the catalog classes as special access. */
    val specialAccessPermissions: Set<String>
        get() = entries.filter { it.special }.map { it.permission }.toSet()

    /**
     * Enrich a bare permission name with catalog metadata, preserving any protection
     * level the device already reported. The device is authoritative; the catalog
     * only fills gaps.
     */
    fun enrich(record: PermissionRecord): PermissionRecord {
        val entry = byName[record.name]
        return record.copy(
            category = entry?.category ?: Category.UNCATEGORIZED,
            protection = when {
                record.protection != Protection.UNKNOWN -> record.protection
                entry != null -> entry.protection
                else -> Protection.UNKNOWN
            }
        )
    }

    /**
     * Device-wide category rollup — PS-01 must-have 03 ("shown per app and as a
     * device-wide summary").
     *
     * @param perApp package name -> that app's permission records.
     * @param topN how many worst offenders to name per category.
     */
    fun summarise(
        perApp: Map<String, List<PermissionRecord>>,
        topN: Int = 3
    ): List<CategorySummary> = Category.entries.map { category ->
        var appsWithGranted = 0
        var appsWithDeclaredOnly = 0
        var grantedCount = 0
        val contribution = mutableListOf<Pair<String, Double>>()

        perApp.forEach { (pkg, records) ->
            val inCategory = records.filter { it.category == category }
            if (inCategory.isEmpty()) return@forEach

            val granted = inCategory.filter { it.granted }
            if (granted.isNotEmpty()) {
                appsWithGranted++
                grantedCount += granted.size
                contribution += pkg to granted.sumOf { sensitivityOf(it.name) }
            } else {
                appsWithDeclaredOnly++
            }
        }

        CategorySummary(
            category = category,
            appsWithGranted = appsWithGranted,
            appsWithDeclaredOnly = appsWithDeclaredOnly,
            grantedPermissionCount = grantedCount,
            worstOffenders = contribution
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .take(topN)
                .map { it.first }
        )
    }

    companion object {
        /** Path of the bundled data file on the classpath. */
        const val RESOURCE_PATH = "/permission_catalog.json"
    }
}
