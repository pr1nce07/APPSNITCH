package com.riskauditor.catalog

import com.riskauditor.model.Category
import com.riskauditor.model.Protection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire format of `permission_catalog.json`. Kept separate from [CatalogEntry]
 *  so the domain type carries no serialization annotations. */
@Serializable
internal data class CatalogFile(
    @SerialName("version") val version: Int = 1,
    @SerialName("source_note") val sourceNote: String = "",
    @SerialName("entries") val entries: List<CatalogEntryDto> = emptyList()
)

@Serializable
internal data class CatalogEntryDto(
    @SerialName("permission") val permission: String,
    @SerialName("category") val category: Category,
    @SerialName("protection") val protection: Protection,
    @SerialName("sensitivity") val sensitivity: Double,
    @SerialName("plain_text") val plainText: String,
    @SerialName("min_api") val minApi: Int = 0,
    @SerialName("max_api") val maxApi: Int? = null,
    @SerialName("special") val special: Boolean = false
) {
    fun toEntry(): CatalogEntry = CatalogEntry(
        permission = permission,
        category = category,
        protection = protection,
        sensitivity = sensitivity,
        plainText = plainText,
        minApi = minApi,
        maxApi = maxApi,
        special = special
    )
}

/**
 * Reads the bundled permission catalog.
 *
 * Separated from [PermissionCatalog] so that the catalog itself has no I/O or
 * serialization dependency and can be built directly in tests.
 */
object CatalogLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        allowComments = true
    }

    /** Parse catalog JSON text. Throws on malformed input — a corrupt bundled asset is a build error. */
    fun fromJson(text: String): PermissionCatalog {
        val file = json.decodeFromString<CatalogFile>(text)
        return PermissionCatalog(file.entries.map { it.toEntry() })
    }

    /** Load the catalog bundled on the classpath at [PermissionCatalog.RESOURCE_PATH]. */
    fun load(): PermissionCatalog {
        val stream = CatalogLoader::class.java.getResourceAsStream(PermissionCatalog.RESOURCE_PATH)
            ?: error("permission catalog missing from the classpath at ${PermissionCatalog.RESOURCE_PATH}")
        return stream.bufferedReader().use { fromJson(it.readText()) }
    }

    /** Memoised default instance. The catalog is immutable, so one copy is enough. */
    val default: PermissionCatalog by lazy { load() }
}
