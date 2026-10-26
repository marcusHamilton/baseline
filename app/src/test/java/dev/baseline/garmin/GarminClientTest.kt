package dev.baseline.garmin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.baseline.prefs.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class GarminClientTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private lateinit var server: MockWebServer
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private lateinit var vault: ApiKeyVault
    private lateinit var prefs: UserPrefs

    /** Mint responses (JWT text, or "" for a 401) are popped per mint; the last one repeats. */
    private val mintJwts = ConcurrentLinkedQueue<String>()
    private var lastMint: String? = null
    @Volatile private var mintOverride: MockResponse? = null
    private val mintCount = AtomicInteger()
    private val dataCodes = ConcurrentLinkedQueue<MockResponse>()
    private val recorded = ConcurrentLinkedQueue<RecordedRequest>()

    @Volatile private var onDataRequest: () -> Unit = {}

    private val mintPath = "/personalapitoken-service/personal-api/personal-api-key/token"

    private fun jwt(expEpochSec: Long): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString("""{"exp":$expEpochSec}""".toByteArray())
        return "$header.$payload.sig"
    }

    private fun jwtIn(seconds: Long) = jwt(now.epochSecond + seconds)

    @Before
    fun setUp() {
        file = File.createTempFile("client", ".preferences_pb").also { it.delete() }
        scope = CoroutineScope(Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { file }
        vault = ApiKeyVault(store, { it }, { it }, clock)
        prefs = UserPrefs(store)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded.add(request)
                if (request.path == mintPath) {
                    mintCount.incrementAndGet()
                    mintOverride?.let { return it }
                    if (mintJwts.isNotEmpty()) lastMint = mintJwts.poll()
                    return lastMint?.takeIf { it.isNotEmpty() }?.let { MockResponse().setBody("  $it\n") } ?: MockResponse().setResponseCode(401)
                }
                if (request.path != mintPath) onDataRequest()
                return dataCodes.poll() ?: MockResponse().setResponseCode(200).setBody("""{"ok":1}""")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        file.delete()
    }

    private fun tokens(
        encrypt: (ByteArray) -> ByteArray = { it },
        decrypt: (ByteArray) -> ByteArray = { it },
    ) = TokenProvider(server.url("/"), OkHttpClient(), vault, prefs, encrypt, decrypt, clock)

    private fun client(tp: TokenProvider = tokens()) = GarminClient(
        server.url("/"),
        OkHttpClient.Builder().authenticator(GarminAuthenticator(tp)).build(),
        tp,
    )

    private fun dataRequests() = recorded.filter { it.path != mintPath }

    @Test
    fun mintsWithApiKeyAndSendsBearer() = runBlocking {
        vault.save("k", 365)
        val token = jwtIn(7200)
        mintJwts.add(token)
        val result = client().fetch(GarminEndpoint.PROFILE, "")
        assertTrue(result is GarminResult.Ok)
        val reqs = recorded.toList()
        assertEquals("POST", reqs[0].method)
        assertEquals(mintPath, reqs[0].path)
        assertEquals("ApiKey k", reqs[0].getHeader("Authorization"))
        assertEquals("*/*", reqs[0].getHeader("Accept"))
        assertEquals(0L, reqs[0].bodySize)
        assertEquals("GET", reqs[1].method)
        assertEquals("/userprofile-service/userprofile/settings", reqs[1].path)
        assertEquals("Bearer $token", reqs[1].getHeader("Authorization"))
        assertEquals("application/json", reqs[1].getHeader("Accept"))
    }

    @Test
    fun reusesTokenUntil60MinutesBeforeExp() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(2 * 3600))
        val c = client()
        c.fetch(GarminEndpoint.PROFILE, "")
        c.fetch(GarminEndpoint.PROFILE, "")
        assertEquals(1, mintCount.get())

    }

    @Test
    fun reMintsWhenLessThan60MinutesRemain() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(59 * 60))
        val c = client()
        c.fetch(GarminEndpoint.PROFILE, "")
        c.fetch(GarminEndpoint.PROFILE, "")
        assertEquals(2, mintCount.get())
    }

    @Test
    fun cachedJwtIsReusedAcrossProvidersAndStoredEncrypted() = runBlocking {
        vault.save("k", 365)
        val token = jwtIn(7200)
        mintJwts.add(token)
        val xor: (ByteArray) -> ByteArray = { b -> ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() } }
        assertEquals(token, tokens(xor, xor).token())
        assertFalse(String(prefs.cachedJwt.first()!!).contains("exp"))
        assertEquals(token, tokens(xor, xor).token())
        assertEquals(1, mintCount.get())
    }

    @Test
    fun undecryptableCachedJwtLeadsToFreshMint() = runBlocking {
        vault.save("k", 365)
        prefs.setCachedJwt("garbage".toByteArray())
        val token = jwtIn(7200)
        mintJwts.add(token)
        val tp = tokens(decrypt = { throw GeneralSecurityException("lost key") })
        assertEquals(token, tp.token())
        assertEquals(1, mintCount.get())
        assertArrayEquals(token.toByteArray(), prefs.cachedJwt.first())
    }

    @Test
    fun missingKeyIsKeyInvalidWithoutMarkingInvalid() = runBlocking {
        val result = client().fetch(GarminEndpoint.PROFILE, "")
        assertEquals(GarminResult.KeyInvalid, result)
        assertEquals(KeyStatus.Missing, vault.status.first())
        assertEquals(0, mintCount.get())
    }

    @Test
    fun mint401IsKeyInvalidAndMarksInvalid() = runBlocking {
        vault.save("k", 365)
        mintJwts.add("")
        assertEquals(GarminResult.KeyInvalid, client().fetch(GarminEndpoint.PROFILE, ""))
        assertEquals(KeyStatus.Invalid, vault.status.first())
        assertTrue(dataRequests().isEmpty())
    }

    @Test
    fun successfulDataCallClearsTheInvalidFlag() = runBlocking {
        vault.save("k", 365)
        vault.markInvalid()
        mintJwts.add(jwtIn(7200))
        assertTrue(client().fetch(GarminEndpoint.PROFILE, "") is GarminResult.Ok)
        assertTrue(vault.status.first() is KeyStatus.Ok)
    }

    @Test
    fun requestsAreSerialised() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        onDataRequest = {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(150)
            inFlight.decrementAndGet()
        }
        val c = client()
        val results = List(3) { async(Dispatchers.IO) { c.fetch(GarminEndpoint.PROFILE, "") } }.awaitAll()
        assertTrue(results.all { it is GarminResult.Ok })
        assertEquals(1, maxInFlight.get())
    }

    private fun assertMintGarbageIsTransient(body: String) = runBlocking {
        vault.save("k", 365)
        mintOverride = MockResponse().setBody(body)
        try {
            client().fetch(GarminEndpoint.PROFILE, "")
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        assertNull(prefs.cachedJwt.first())
        assertTrue(vault.status.first() is KeyStatus.Ok)
        assertTrue(dataRequests().isEmpty())
    }

    @Test
    fun blankMintBodyIsTransientIoFailure() = assertMintGarbageIsTransient("  \n")

    @Test
    fun htmlMintBodyIsTransientIoFailure() = assertMintGarbageIsTransient("<html>\n<body>Oops</body></html>")

    @Test
    fun mintBodyWithoutNumericExpIsTransientIoFailure() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val payload = enc.encodeToString("""{"exp":"soon"}""".toByteArray())
        assertMintGarbageIsTransient("a.$payload.c")
    }

    @Test
    fun mint429And5xxAreRateLimited() = runBlocking {
        vault.save("k", 365)
        mintOverride = MockResponse().setResponseCode(429)
        assertEquals(GarminResult.RateLimited(429), client().fetch(GarminEndpoint.PROFILE, ""))
        mintOverride = MockResponse().setResponseCode(503)
        assertEquals(GarminResult.RateLimited(503), client().fetch(GarminEndpoint.PROFILE, ""))
        assertTrue(vault.status.first() is KeyStatus.Ok)
    }

    @Test
    fun rateLimitedReMintInsideAuthenticatorIsRateLimited() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        dataCodes.add(MockResponse().setResponseCode(401))
        val c = client()
        // First mint succeeds; the re-mint triggered by the 401 gets 429.
        server.dispatcher.let { real ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == mintPath && mintCount.get() >= 1) mintOverride = MockResponse().setResponseCode(429)
                    return real.dispatch(request)
                }
            }
        }
        assertEquals(GarminResult.RateLimited(429), c.fetch(GarminEndpoint.PROFILE, ""))
        assertTrue(vault.status.first() is KeyStatus.Ok)
    }

    @Test
    fun fetchDoesNotBlockTheCallerThread() {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            runBlocking(exec.asCoroutineDispatcher()) {
                vault.save("k", 365)
                mintJwts.add(jwtIn(7200))
                val names = ConcurrentLinkedQueue<String>()
                val http = OkHttpClient.Builder()
                    .addInterceptor { chain -> names.add(Thread.currentThread().name); chain.proceed(chain.request()) }
                    .build()
                val tp = TokenProvider(server.url("/"), http, vault, prefs, { it }, { it }, clock)
                GarminClient(server.url("/"), http, tp).fetch(GarminEndpoint.PROFILE, "")
                assertEquals(2, names.size) // mint + data
                assertTrue(names.none { it == "fake-main" })
            }
        } finally {
            exec.shutdown()
        }
    }

    @Test
    fun reMintsOnceOn401ThenSucceeds() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        dataCodes.add(MockResponse().setResponseCode(401))
        dataCodes.add(MockResponse().setResponseCode(200).setBody("""{"a":1}"""))
        val result = client().fetch(GarminEndpoint.PROFILE, "")
        assertTrue(result is GarminResult.Ok)
        assertEquals(2, mintCount.get())
        assertEquals(2, dataRequests().size)
    }

    @Test
    fun secondConsecutive401IsKeyInvalid() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        dataCodes.add(MockResponse().setResponseCode(401))
        dataCodes.add(MockResponse().setResponseCode(401))
        assertEquals(GarminResult.KeyInvalid, client().fetch(GarminEndpoint.PROFILE, ""))
        assertEquals(KeyStatus.Invalid, vault.status.first())
        assertEquals(2, mintCount.get())
    }

    @Test
    fun emptyDataAnd204AreEmpty() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        val c = client()
        dataCodes.add(MockResponse().setResponseCode(204))
        assertEquals(GarminResult.Empty, c.fetch(GarminEndpoint.DAILY_SUMMARIES, "2025-01-01..2025-01-02"))
        dataCodes.add(MockResponse().setBody("""{"data":[]}"""))
        assertEquals(GarminResult.Empty, c.fetch(GarminEndpoint.DAILY_SUMMARIES, "2025-01-01..2025-01-02"))
        dataCodes.add(MockResponse().setBody(""" { "data" : [ ] } """))
        assertEquals(GarminResult.Empty, c.fetch(GarminEndpoint.DAILY_SUMMARIES, "2025-01-01..2025-01-02"))
        dataCodes.add(MockResponse().setBody("[]"))
        assertEquals(GarminResult.Empty, c.fetch(GarminEndpoint.DAILY_SUMMARIES, "2025-01-01..2025-01-02"))
        dataCodes.add(MockResponse().setBody(""))
        assertEquals(GarminResult.Empty, c.fetch(GarminEndpoint.DAILY_SUMMARIES, "2025-01-01..2025-01-02"))
        dataCodes.add(MockResponse().setBody("""{"data":[1]}"""))
        assertTrue(c.fetch(GarminEndpoint.DAILY_SUMMARIES, "2025-01-01..2025-01-02") is GarminResult.Ok)
        dataCodes.add(MockResponse().setBody("""{"data":[],"x":1}"""))
        assertTrue(c.fetch(GarminEndpoint.DAILY_SUMMARIES, "2025-01-01..2025-01-02") is GarminResult.Ok)
    }

    @Test
    fun okCarriesBodyAndContentType() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        dataCodes.add(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"a":1}"""))
        val ok = client().fetch(GarminEndpoint.PROFILE, "") as GarminResult.Ok
        assertEquals("""{"a":1}""", String(ok.body))
        assertEquals("application/json", ok.contentType)
    }

    @Test
    fun rateLimitAnd5xxAreRateLimited() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        val c = client()
        dataCodes.add(MockResponse().setResponseCode(429))
        assertEquals(GarminResult.RateLimited(429), c.fetch(GarminEndpoint.PROFILE, ""))
        dataCodes.add(MockResponse().setResponseCode(503))
        assertEquals(GarminResult.RateLimited(503), c.fetch(GarminEndpoint.PROFILE, ""))
    }

    @Test
    fun otherStatusesMap() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        val c = client()
        dataCodes.add(MockResponse().setResponseCode(400).setBody("bad dates"))
        assertEquals(GarminResult.BadRequest("bad dates"), c.fetch(GarminEndpoint.PROFILE, ""))
        dataCodes.add(MockResponse().setResponseCode(403))
        assertEquals(GarminResult.Failed(403, "Client Error"), c.fetch(GarminEndpoint.PROFILE, ""))
        assertEquals(KeyStatus.Ok(java.time.LocalDate.of(2027, 10, 24), 365), vault.status.first())
        dataCodes.add(MockResponse().setResponseCode(406))
        val failed = c.fetch(GarminEndpoint.PROFILE, "") as GarminResult.Failed
        assertEquals(406, failed.code)
    }

    @Test
    fun ioExceptionPropagates() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        val c = client()
        server.shutdown()
        try {
            c.fetch(GarminEndpoint.PROFILE, "")
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun fitUsesExactAcceptHeader() = runBlocking {
        vault.save("k", 365)
        mintJwts.add(jwtIn(7200))
        dataCodes.add(MockResponse().setHeader("Content-Type", "application/zip").setBody("PK\u0003\u0004zipbytes"))
        val r = client().fetch(GarminEndpoint.FIT, "12345")
        assertTrue(r is GarminResult.Ok)
        val req = dataRequests().single()
        assertEquals("/download-service/files/activity/12345", req.path)
        assertEquals("application/zip, application/x-zip-compressed, application/octet-stream", req.getHeader("Accept"))
    }

    @Test
    fun endpointPathsMatchSpec() {
        val hrv = GarminEndpoint.HRV_RANGE.request("2026-04-27..2026-10-23")
        assertEquals("/hrv-service/hrv/daily/2026-04-27/2026-10-23", hrv.path)
        assertTrue(hrv.query.isEmpty())
        val sleep = GarminEndpoint.SLEEP_DAY.request("2026-10-23")
        assertEquals("/sleep-service/sleep/dailySleep", sleep.path)
        assertEquals(mapOf("date" to "2026-10-23"), sleep.query)
        assertEquals(mapOf("start" to "100", "limit" to "50"), GarminEndpoint.ACTIVITY_PAGE.request("2").query)
        val range = GarminEndpoint.ACTIVITY_RANGE.request("2026-10-01..2026-10-07")
        assertEquals("/activitylist-service/activities/search/activities", range.path)
        assertEquals(
            mapOf("startDate" to "2026-10-01", "endDate" to "2026-10-07", "limit" to "50", "start" to "0"),
            range.query,
        )
        val daily = GarminEndpoint.DAILY_SUMMARIES.request("2026-10-01..2026-10-07")
        assertEquals("/usersummary-service/v2/dailysummaries", daily.path)
        assertEquals(mapOf("startDate" to "2026-10-01", "endDate" to "2026-10-07"), daily.query)
        assertEquals("/hrv-service/hrv/2026-10-23", GarminEndpoint.HRV_DAY.request("2026-10-23").path)
        assertEquals(
            "/wellness-service/wellness/daily/spo2acclimation/2026-10-23",
            GarminEndpoint.SPO2_DAY.request("2026-10-23").path,
        )
        assertEquals(
            "/wellness-service/wellness/dailyHeartRate",
            GarminEndpoint.HEART_RATE_DAY.request("2026-10-23").path,
        )
        assertEquals("/activity-service/activity/99", GarminEndpoint.ACTIVITY_DETAIL.request("99").path)
        assertEquals("/biometric-service/heartRateZones/v2", GarminEndpoint.HR_ZONES.request("").path)
        assertEquals(
            "/usersummary-service/stats/steps/daily/2026-10-01/2026-10-07",
            GarminEndpoint.STEPS.request("2026-10-01..2026-10-07").path,
        )
        assertEquals(
            "/usersummary-service/stats/stress/daily/2026-10-01/2026-10-07",
            GarminEndpoint.STRESS.request("2026-10-01..2026-10-07").path,
        )
        assertEquals(
            mapOf("startDate" to "2026-10-01", "endDate" to "2026-10-07", "chunks" to "288"),
            GarminEndpoint.BODY_BATTERY.request("2026-10-01..2026-10-07").query,
        )
        assertEquals(
            "/sleep-service/sleep/dailySleepSummariesByDate",
            GarminEndpoint.SLEEP_SUMMARIES.request("2026-10-01..2026-10-07").path,
        )
        assertEquals(180, GarminEndpoint.DAILY_SUMMARIES.maxDays)
        assertEquals(7, GarminEndpoint.ACTIVITY_RANGE.maxDays)
    }

    @Test
    fun authorizationNeverLogged() = runBlocking {
        vault.save("super-secret-api-key", 365)
        val token = jwtIn(7200)
        mintJwts.add(token)
        val lines = ConcurrentLinkedQueue<String>()
        val logging = GarminClient.loggingInterceptor { lines.add(it) }
        val http = OkHttpClient.Builder().addInterceptor(logging).build()
        val tp = TokenProvider(server.url("/"), http, vault, prefs, { it }, { it }, clock)
        val c = GarminClient(server.url("/"), http, tp)
        c.fetch(GarminEndpoint.PROFILE, "")
        val log = lines.joinToString("\n")
        assertTrue("logger saw nothing", log.contains("GET"))
        assertFalse(log.contains("super-secret-api-key"))
        assertFalse(log.contains(token))
        assertFalse(log.contains(token.substringAfter('.').substringBefore('.')))
        assertTrue(log.contains("Authorization: ██"))
    }
}
