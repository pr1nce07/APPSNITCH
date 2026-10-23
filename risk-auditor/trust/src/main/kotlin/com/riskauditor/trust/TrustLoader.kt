package com.riskauditor.trust

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class TrustFile(
    @SerialName("version") val version: Int = 1,
    @SerialName("note") val note: String = "",
    @SerialName("tools") val tools: List<TrustedToolDto> = emptyList()
)

@Serializable
internal data class TrustedToolDto(
    @SerialName("package_name") val packageName: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("kind") val kind: TrustedToolKind,
    /** Lowercase hex SHA-256 of signing certificates. Empty means "no pin shipped". */
    @SerialName("cert_sha256") val certSha256: List<String> = emptyList()
) {
    fun toTool(): TrustedTool = TrustedTool(
        packageName = packageName,
        displayName = displayName,
        kind = kind,
        certSha256 = certSha256.map { it.lowercase() }.toSet()
    )
}

object TrustLoader {

    private val json = Json { ignoreUnknownKeys = true }

    fun fromJson(text: String): TrustRegistry {
        val file = json.decodeFromString<TrustFile>(text)
        return TrustRegistry(file.tools.map { it.toTool() })
    }

    fun load(): TrustRegistry {
        val stream = TrustLoader::class.java.getResourceAsStream(TrustRegistry.RESOURCE_PATH)
            ?: return TrustRegistry.EMPTY
        return stream.bufferedReader().use { fromJson(it.readText()) }
    }

    val default: TrustRegistry by lazy { load() }
}
