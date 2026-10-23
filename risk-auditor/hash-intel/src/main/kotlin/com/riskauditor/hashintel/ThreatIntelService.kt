package com.riskauditor.hashintel

import com.riskauditor.model.HashVerdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates fingerprint lookups.
 *
 * Responsibilities, in order of importance:
 *
 *  1. **Enforce consent.** The gate lives here rather than in each provider so
 *     that adding a provider cannot accidentally bypass it. With consent off,
 *     [lookup] returns [HashVerdict.SkippedNoConsent] without constructing a
 *     request at all.
 *  2. **Stop on rate limiting.** Public tiers are tightly limited. On the first
 *     429 the service stops calling that provider for the rest of the batch
 *     rather than burning the quota and getting the user's key blocked.
 *  3. **Degrade to offline.** Providers that work offline are always consulted;
 *     network ones are skipped silently when unreachable, so a scan in airplane
 *     mode produces the same verdicts the offline provider would give.
 */
class ThreatIntelService(
    private val consent: ConsentManager,
    private val providers: List<ThreatIntelProvider>,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Look up one fingerprint.
     *
     * Offline providers run even without consent, because they send nothing
     * anywhere. Only network providers are gated.
     */
    suspend fun lookup(sha256: String): HashVerdict = withContext(io) {
        val offline = providers.filter { it.worksOffline }
        val online = providers.filter { !it.worksOffline }

        // Offline first: free, instant, and the only thing that works in airplane mode.
        offline.forEach { provider ->
            val verdict = provider.lookup(sha256)
            if (verdict.isConclusive()) return@withContext verdict
        }

        if (!consent.isGranted) {
            return@withContext if (online.isEmpty()) {
                HashVerdict.Unknown(offline.firstOrNull()?.name ?: "local")
            } else {
                HashVerdict.SkippedNoConsent
            }
        }

        var lastError: HashVerdict.Error? = null
        online.forEach { provider ->
            when (val verdict = provider.lookup(sha256)) {
                is HashVerdict.Error -> lastError = verdict
                else -> if (verdict.isConclusive()) return@withContext verdict
            }
        }

        lastError ?: HashVerdict.Unknown(online.firstOrNull()?.name ?: "none")
    }

    /**
     * Look up a batch, stopping network calls early when a provider rate-limits.
     *
     * @return package name -> verdict.
     */
    suspend fun lookupAll(hashesByPackage: Map<String, String>): Map<String, HashVerdict> =
        withContext(io) {
            val results = LinkedHashMap<String, HashVerdict>()
            var networkGaveUp = false

            hashesByPackage.forEach { (pkg, hash) ->
                if (networkGaveUp && consent.isGranted) {
                    // Keep going with offline providers only.
                    results[pkg] = offlineOnly(hash)
                    return@forEach
                }

                val verdict = lookup(hash)
                if (verdict is HashVerdict.Error &&
                    verdict.message == VirusTotalProvider.RATE_LIMITED
                ) {
                    networkGaveUp = true
                }
                results[pkg] = verdict
            }

            results
        }

    private fun offlineOnly(sha256: String): HashVerdict {
        providers.filter { it.worksOffline }.forEach { provider ->
            val verdict = provider.lookup(sha256)
            if (verdict.isConclusive()) return verdict
        }
        return HashVerdict.NotLookedUp
    }

    private fun HashVerdict.isConclusive(): Boolean = when (this) {
        is HashVerdict.Malicious, is HashVerdict.Suspicious, is HashVerdict.Clean -> true
        else -> false
    }
}
