package com.riskauditor.hashintel

import com.riskauditor.model.HashVerdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A source of verdicts about an APK fingerprint.
 *
 * Implementations MUST send the hash and nothing else. No package name, no
 * device identifier, no label, no APK bytes. `ThreatIntelServiceTest` asserts
 * this against a MockWebServer by inspecting the outgoing request bodies.
 */
interface ThreatIntelProvider {
    val name: String
    /** True when this provider can work with no network at all. */
    val worksOffline: Boolean
    fun lookup(sha256: String): HashVerdict
}

/**
 * Offline provider backed by a bundled list of known-bad fingerprints.
 *
 * The shipped list contains ONLY hashes of this project's own inert test
 * fixtures (see `:fixture-apps`). No real malware hash is distributed, which
 * keeps the build compliant with PS-01's "no live malware" constraint while
 * still letting the demo show a MALICIOUS verdict end to end with the network
 * switched off.
 */
class LocalBlocklistProvider(
    private val blocklist: Set<String> = emptySet()
) : ThreatIntelProvider {

    override val name: String = "local"
    override val worksOffline: Boolean = true

    override fun lookup(sha256: String): HashVerdict =
        if (sha256.lowercase() in blocklist) {
            HashVerdict.Malicious(name, detections = 1)
        } else {
            HashVerdict.Unknown(name)
        }

    companion object {
        const val RESOURCE_PATH = "/local_blocklist.json"
    }
}

/**
 * VirusTotal v3 file lookup.
 *
 * Needs an API key the user supplies themselves; none is shipped. The public
 * tier is rate-limited, so [ThreatIntelService] handles 429 by stopping rather
 * than hammering.
 */
class VirusTotalProvider(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://www.virustotal.com/api/v3/"
) : ThreatIntelProvider {

    override val name: String = "virustotal"
    override val worksOffline: Boolean = false

    override fun lookup(sha256: String): HashVerdict {
        if (apiKey.isBlank()) return HashVerdict.Error(name, "no api key configured")

        // The hash is the entire request. Nothing else identifies the user.
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/files/$sha256")
            .header("x-apikey", apiKey)
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> parse(response.body?.string().orEmpty())
                    404 -> HashVerdict.Unknown(name)   // not in the corpus; not a failure
                    401, 403 -> HashVerdict.Error(name, "api key rejected")
                    429 -> HashVerdict.Error(name, RATE_LIMITED)
                    else -> HashVerdict.Error(name, "http ${response.code}")
                }
            }
        } catch (e: IOException) {
            HashVerdict.Error(name, "offline or unreachable")
        } catch (t: Throwable) {
            HashVerdict.Error(name, t.message ?: "unexpected failure")
        }
    }

    private fun parse(body: String): HashVerdict = try {
        val stats = Json.parseToJsonElement(body)
            .jsonObject["data"]?.jsonObject
            ?.get("attributes")?.jsonObject
            ?.get("last_analysis_stats")?.jsonObject

        val malicious = stats?.get("malicious")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val suspicious = stats?.get("suspicious")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        when {
            malicious >= MALICIOUS_THRESHOLD -> HashVerdict.Malicious(name, malicious)
            malicious > 0 || suspicious > 0 -> HashVerdict.Suspicious(name, malicious + suspicious)
            stats != null -> HashVerdict.Clean(name)
            else -> HashVerdict.Unknown(name)
        }
    } catch (t: Throwable) {
        HashVerdict.Error(name, "unreadable response")
    }

    companion object {
        /**
         * One or two engines flagging a file is routinely a false positive, so a
         * single detection is reported as SUSPICIOUS rather than MALICIOUS.
         * MALICIOUS carries a band floor of CRITICAL in the rubric and should
         * require real agreement between engines.
         */
        const val MALICIOUS_THRESHOLD = 3
        const val RATE_LIMITED = "rate limited"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/**
 * abuse.ch MalwareBazaar lookup.
 *
 * Verified against https://bazaar.abuse.ch/api/ on 2026-10-16: every request
 * requires an `Auth-Key` header, obtainable free from auth.abuse.ch. The
 * endpoint is a POST to https://mb-api.abuse.ch/api/v1/ with form fields
 * `query=get_info` and `hash=<sha256>`.
 */
class MalwareBazaarProvider(
    private val authKey: String,
    private val client: OkHttpClient = VirusTotalProvider.defaultClient(),
    private val baseUrl: String = "https://mb-api.abuse.ch/api/v1/"
) : ThreatIntelProvider {

    override val name: String = "malwarebazaar"
    override val worksOffline: Boolean = false

    override fun lookup(sha256: String): HashVerdict {
        if (authKey.isBlank()) return HashVerdict.Error(name, "no auth key configured")

        val body = FormBody.Builder()
            .add("query", "get_info")
            .add("hash", sha256)
            .build()

        val request = Request.Builder()
            .url(baseUrl)
            .header("Auth-Key", authKey)
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return if (response.code == 429) {
                        HashVerdict.Error(name, VirusTotalProvider.RATE_LIMITED)
                    } else {
                        HashVerdict.Error(name, "http ${response.code}")
                    }
                }
                parse(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            HashVerdict.Error(name, "offline or unreachable")
        } catch (t: Throwable) {
            HashVerdict.Error(name, t.message ?: "unexpected failure")
        }
    }

    private fun parse(body: String): HashVerdict = try {
        val root = Json.parseToJsonElement(body).jsonObject
        when (root["query_status"]?.jsonPrimitive?.content) {
            "ok" -> HashVerdict.Malicious(name, detections = 1)
            "hash_not_found", "no_results" -> HashVerdict.Unknown(name)
            "illegal_hash" -> HashVerdict.Error(name, "malformed hash")
            "unauthenticated", "unauthorized" -> HashVerdict.Error(name, "auth key rejected")
            else -> HashVerdict.Unknown(name)
        }
    } catch (t: Throwable) {
        HashVerdict.Error(name, "unreadable response")
    }
}
