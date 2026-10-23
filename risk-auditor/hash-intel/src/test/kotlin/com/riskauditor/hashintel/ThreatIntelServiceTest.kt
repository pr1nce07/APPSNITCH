package com.riskauditor.hashintel

import com.google.common.truth.Truth.assertThat
import com.riskauditor.model.HashVerdict
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThreatIntelServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun vtProvider(apiKey: String = "test-key") = VirusTotalProvider(
        apiKey = apiKey,
        baseUrl = server.url("/api/v3/").toString()
    )

    // ------------------------------------------------------------------
    // The three privacy assertions that matter most
    // ------------------------------------------------------------------

    @Test
    fun `NO network request is made without consent`() = runTest {
        val service = ThreatIntelService(
            consent = ConsentManager(InMemoryConsentStore(granted = false)),
            providers = listOf(vtProvider())
        )

        val verdict = service.lookup("a".repeat(64))

        assertThat(verdict).isEqualTo(HashVerdict.SkippedNoConsent)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `revoking consent stops requests immediately`() = runTest {
        val consent = ConsentManager(InMemoryConsentStore(granted = true))
        server.enqueue(MockResponse().setResponseCode(404))

        val service = ThreatIntelService(consent, listOf(vtProvider()))
        service.lookup("b".repeat(64))
        assertThat(server.requestCount).isEqualTo(1)

        consent.revoke()
        val after = service.lookup("c".repeat(64))

        assertThat(after).isEqualTo(HashVerdict.SkippedNoConsent)
        assertThat(server.requestCount).isEqualTo(1)   // unchanged
    }

    @Test
    fun `the request contains ONLY the hash - no package name, device id or apk bytes`() = runTest {
        val hash = "d".repeat(64)
        server.enqueue(MockResponse().setResponseCode(404))

        val service = ThreatIntelService(
            consent = ConsentManager(InMemoryConsentStore(granted = true)),
            providers = listOf(vtProvider())
        )
        service.lookup(hash)

        val recorded = server.takeRequest()

        // The hash is the entire payload: it appears in the path and nowhere else.
        assertThat(recorded.path).contains(hash)
        assertThat(recorded.body.size).isEqualTo(0L)

        val everythingSent = buildString {
            append(recorded.requestLine)
            append(recorded.headers.toString())
            append(recorded.body.readUtf8())
        }

        listOf(
            "com.example", "packageName", "package_name",
            "android_id", "deviceId", "imei", "serial",
            "label", "versionName"
        ).forEach { forbidden ->
            assertThat(everythingSent).doesNotContain(forbidden)
        }
    }

    // ------------------------------------------------------------------
    // Verdict mapping
    // ------------------------------------------------------------------

    @Test
    fun `many detections map to MALICIOUS`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"attributes":{"last_analysis_stats":
                   {"malicious":41,"suspicious":2,"undetected":20,"harmless":0}}}}"""
            )
        )
        val verdict = vtProvider().lookup("e".repeat(64))
        assertThat(verdict).isInstanceOf(HashVerdict.Malicious::class.java)
        assertThat((verdict as HashVerdict.Malicious).detections).isEqualTo(41)
    }

    @Test
    fun `a single detection maps to SUSPICIOUS, not MALICIOUS`() = runTest {
        // One engine flagging a file is routinely a false positive. MALICIOUS
        // forces a CRITICAL band, so it must require agreement between engines.
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"attributes":{"last_analysis_stats":
                   {"malicious":1,"suspicious":0,"undetected":60,"harmless":0}}}}"""
            )
        )
        val verdict = vtProvider().lookup("f".repeat(64))
        assertThat(verdict).isInstanceOf(HashVerdict.Suspicious::class.java)
    }

    @Test
    fun `zero detections map to CLEAN`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"attributes":{"last_analysis_stats":
                   {"malicious":0,"suspicious":0,"undetected":72,"harmless":5}}}}"""
            )
        )
        assertThat(vtProvider().lookup("1".repeat(64))).isInstanceOf(HashVerdict.Clean::class.java)
    }

    @Test
    fun `a 404 means unknown, not an error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(vtProvider().lookup("2".repeat(64))).isInstanceOf(HashVerdict.Unknown::class.java)
    }

    @Test
    fun `a rejected api key is reported as an error, not a clean verdict`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val verdict = vtProvider().lookup("3".repeat(64))
        assertThat(verdict).isInstanceOf(HashVerdict.Error::class.java)
    }

    @Test
    fun `an unreadable response does not throw`() = runTest {
        server.enqueue(MockResponse().setBody("this is not json"))
        assertThat(vtProvider().lookup("4".repeat(64))).isInstanceOf(HashVerdict.Error::class.java)
    }

    // ------------------------------------------------------------------
    // Offline behaviour
    // ------------------------------------------------------------------

    @Test
    fun `the offline provider works with no consent and no network`() = runTest {
        val knownBad = "9".repeat(64)
        val service = ThreatIntelService(
            consent = ConsentManager(InMemoryConsentStore(granted = false)),
            providers = listOf(LocalBlocklistProvider(setOf(knownBad)))
        )

        assertThat(service.lookup(knownBad)).isInstanceOf(HashVerdict.Malicious::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `an unreachable network degrades gracefully rather than failing the scan`() = runTest {
        server.shutdown()   // nothing is listening

        val service = ThreatIntelService(
            consent = ConsentManager(InMemoryConsentStore(granted = true)),
            providers = listOf(vtProvider())
        )

        val verdict = service.lookup("5".repeat(64))
        assertThat(verdict).isInstanceOf(HashVerdict.Error::class.java)
    }

    @Test
    fun `rate limiting stops further network calls in a batch`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        // If the service kept going it would consume these too.
        repeat(5) { server.enqueue(MockResponse().setResponseCode(404)) }

        val service = ThreatIntelService(
            consent = ConsentManager(InMemoryConsentStore(granted = true)),
            providers = listOf(vtProvider())
        )

        val batch = (1..6).associate { "com.app$it" to it.toString().repeat(64).take(64) }
        val results = service.lookupAll(batch)

        assertThat(results).hasSize(6)
        assertThat(server.requestCount).isEqualTo(1)
    }

    // ------------------------------------------------------------------
    // Hashing
    // ------------------------------------------------------------------

    @Test
    fun `sha256 matches the published vector for an empty input`() {
        val tmp = Files.createTempFile("empty", ".apk").toFile()
        tmp.writeBytes(ByteArray(0))

        val hash = Sha256Hasher().hash("com.empty", tmp.absolutePath, 1L)

        assertThat(hash).isNotNull()
        assertThat(hash!!.sha256)
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        tmp.delete()
    }

    @Test
    fun `sha256 matches the published vector for abc`() {
        val tmp = Files.createTempFile("abc", ".apk").toFile()
        tmp.writeText("abc")

        val hash = Sha256Hasher().hash("com.abc", tmp.absolutePath, 1L)

        assertThat(hash!!.sha256)
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        tmp.delete()
    }

    @Test
    fun `hashing is cached until the app is updated`() {
        val tmp = Files.createTempFile("cache", ".apk").toFile()
        tmp.writeText("original")
        val hasher = Sha256Hasher()

        val first = hasher.hash("com.cache", tmp.absolutePath, lastUpdateTime = 100L)!!

        // Same lastUpdateTime AND same size: the cached value is returned even
        // though the bytes changed, which is the intended trade-off.
        tmp.writeText("replaced")   // same length as "original"
        val second = hasher.hash("com.cache", tmp.absolutePath, lastUpdateTime = 100L)!!
        assertThat(second.sha256).isEqualTo(first.sha256)

        // A new lastUpdateTime invalidates it.
        val third = hasher.hash("com.cache", tmp.absolutePath, lastUpdateTime = 200L)!!
        assertThat(third.sha256).isNotEqualTo(first.sha256)

        tmp.delete()
    }

    @Test
    fun `a missing apk returns null rather than throwing`() {
        val hash = Sha256Hasher().hash("com.gone", File("/does/not/exist.apk").absolutePath, 1L)
        assertThat(hash).isNull()
    }

    // ------------------------------------------------------------------
    // Consent model
    // ------------------------------------------------------------------

    @Test
    fun `consent defaults to off`() {
        assertThat(ConsentManager().isGranted).isFalse()
        assertThat(ConsentManager().grantedAt).isNull()
    }

    @Test
    fun `granting records when the user agreed and revoking clears it`() {
        val consent = ConsentManager()
        consent.grant(1_700_000_000_000L)
        assertThat(consent.isGranted).isTrue()
        assertThat(consent.grantedAt).isEqualTo(1_700_000_000_000L)

        consent.revoke()
        assertThat(consent.isGranted).isFalse()
        assertThat(consent.grantedAt).isNull()
    }

    @Test
    fun `the disclosure says what is and is not sent`() {
        val text = ConsentManager().disclosure
        assertThat(text).contains("fingerprint")
        assertThat(text).contains("never sent")
        assertThat(text).contains("turn this off")
    }
}
