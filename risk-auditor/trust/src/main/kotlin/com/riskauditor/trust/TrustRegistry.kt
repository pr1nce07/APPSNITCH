package com.riskauditor.trust

import com.riskauditor.model.AppRecord
import com.riskauditor.model.InstallerKind

/**
 * One entry in the security/MDM allowlist.
 *
 * @param packageName the package this entry describes.
 * @param displayName what to call it in a reason sentence.
 * @param certSha256 pinned signing certificates, lowercase hex. May be empty when
 *        we do not have a pin, in which case the entry can only ever be satisfied
 *        by a trusted installer.
 */
data class TrustedTool(
    val packageName: String,
    val displayName: String,
    val kind: TrustedToolKind,
    val certSha256: Set<String> = emptySet()
)

enum class TrustedToolKind {
    /** Mobile device management / enterprise policy agent. Legitimately holds device admin. */
    MDM,

    /** Anti-malware or security suite. Legitimately holds broad access. */
    SECURITY,

    /** Accessibility tool for users with disabilities. Legitimately holds accessibility. */
    ACCESSIBILITY_TOOL,

    /** Parental-control / screen-time tool. Legitimately holds usage stats and admin. */
    PARENTAL_CONTROL
}

/** Why a trust decision came out the way it did. Surfaced in reason evidence. */
enum class TrustBasis {
    /** Signing certificate matched a pin. The strong case. */
    PINNED_CERTIFICATE,

    /** Package is on the allowlist and arrived from a store we trust. */
    ALLOWLISTED_AND_TRUSTED_INSTALLER,

    /** Package name matched the allowlist but nothing corroborates it. NOT trusted. */
    NAME_ONLY_NOT_TRUSTED,

    /** Not on the allowlist at all. */
    NOT_ALLOWLISTED
}

data class TrustVerdict(
    val trusted: Boolean,
    val basis: TrustBasis,
    val tool: TrustedTool? = null
) {
    val displayName: String? get() = tool?.displayName
}

/**
 * Decides whether an app is a legitimate security or management tool.
 *
 * **The central rule: a package-name match alone is never enough.** Package names
 * are trivially spoofable — anyone can build an APK whose applicationId is a
 * well-known MDM agent's. A sideloaded app claiming to be an enterprise agent is
 * exactly the shape a real attack takes, so it must score *higher*, not lower.
 *
 * Trust therefore requires one of:
 *  - a signing certificate matching a pin we shipped, or
 *  - allowlisted package name **and** arrival from Google Play or an OEM store.
 *
 * The bundled allowlist is deliberately tiny and carries no certificate pins,
 * because shipping unverified pins would be worse than shipping none. See
 * `docs/ASSUMPTIONS.md` A-06 and `docs/LIMITATIONS.md` L-05.
 */
class TrustRegistry(tools: List<TrustedTool> = emptyList()) {

    private val byPackage: Map<String, TrustedTool> = tools.associateBy { it.packageName }

    val size: Int get() = byPackage.size

    fun evaluate(app: AppRecord): TrustVerdict {
        val tool = byPackage[app.packageName]
            ?: return TrustVerdict(trusted = false, basis = TrustBasis.NOT_ALLOWLISTED)

        // Strongest signal first: did it sign with a certificate we pinned?
        if (tool.certSha256.isNotEmpty()) {
            val matched = app.signingCertSha256.any { it.lowercase() in tool.certSha256 }
            return if (matched) {
                TrustVerdict(true, TrustBasis.PINNED_CERTIFICATE, tool)
            } else {
                // Right name, wrong signature. This is the spoof case and it is
                // deliberately reported as NOT trusted.
                TrustVerdict(false, TrustBasis.NAME_ONLY_NOT_TRUSTED, tool)
            }
        }

        // No pin available: fall back to provenance.
        val installerIsTrusted = app.installer.kind == InstallerKind.GOOGLE_PLAY ||
            app.installer.kind == InstallerKind.OEM_STORE

        return if (installerIsTrusted) {
            TrustVerdict(true, TrustBasis.ALLOWLISTED_AND_TRUSTED_INSTALLER, tool)
        } else {
            TrustVerdict(false, TrustBasis.NAME_ONLY_NOT_TRUSTED, tool)
        }
    }

    fun isTrustedFor(app: AppRecord, kind: TrustedToolKind): Boolean {
        val verdict = evaluate(app)
        return verdict.trusted && verdict.tool?.kind == kind
    }

    /** True when device admin on this app is expected rather than alarming. */
    fun deviceAdminIsExpected(app: AppRecord): Boolean {
        val verdict = evaluate(app)
        if (!verdict.trusted) return false
        return verdict.tool?.kind == TrustedToolKind.MDM ||
            verdict.tool?.kind == TrustedToolKind.SECURITY ||
            verdict.tool?.kind == TrustedToolKind.PARENTAL_CONTROL
    }

    /** True when an accessibility service on this app is expected. */
    fun accessibilityIsExpected(app: AppRecord): Boolean =
        isTrustedFor(app, TrustedToolKind.ACCESSIBILITY_TOOL)

    companion object {
        const val RESOURCE_PATH = "/trusted_tools.json"

        /** An empty registry: nothing is trusted. Safe default for tests. */
        val EMPTY = TrustRegistry()
    }
}
