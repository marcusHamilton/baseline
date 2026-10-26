package dev.baseline.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminAuthenticator
import dev.baseline.garmin.GarminClient
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.garmin.GarminResult
import dev.baseline.garmin.KeyStatus
import dev.baseline.garmin.TokenProvider
import dev.baseline.garmin.saveAndTest
import dev.baseline.prefs.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Testing a candidate key never touches the stored key, its status or the cached JWT. */
@RunWith(RobolectricTestRunner::class)
class TestConnectionTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val mintPath = "/personalapitoken-service/personal-api/personal-api-key/token"
    private val recorded = ConcurrentLinkedQueue<RecordedRequest>()
    private var profileCode = 503
    private var mintCode = 200
    private lateinit var server: MockWebServer
    private lateinit var file: File

    private fun jwt(): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
            enc.encodeToString("""{"exp":${now.epochSecond + 7200}}""".toByteArray()) + ".sig"
    }

    @Before
    fun setUp() {
        file = File.createTempFile("tokenconn", ".preferences_pb").also { it.delete() }
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recorded.add(request)
                    return if (request.path == mintPath) {
                        MockResponse().setResponseCode(mintCode).setBody(jwt())
                    } else {
                        MockResponse().setResponseCode(profileCode).setBody("""{"ok":1}""")
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        file.delete()
    }

    private class Rig(val vault: ApiKeyVault, val tokens: TokenProvider, val client: GarminClient, val prefs: UserPrefs)

    private fun rig(): Rig {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file }
        val vault = ApiKeyVault(store, { it }, { it }, clock)
        val prefs = UserPrefs(store)
        val tokens = TokenProvider(server.url("/"), OkHttpClient(), vault, prefs, { it }, { it }, clock)
        val client =
            GarminClient(
                server.url("/"),
                OkHttpClient.Builder().authenticator(GarminAuthenticator(tokens)).build(),
                tokens,
            )
        return Rig(vault, tokens, client, prefs)
    }

    private fun mintAuths() = recorded.filter { it.path == mintPath }.map { it.getHeader("Authorization") }

    @Test
    fun rejectedCandidateLeavesTheStoredKeyAndItsStatusAlone() = runBlocking {
        val r = rig()
        r.vault.save("old-key", 365)
        val before = r.vault.status.first()
        mintCode = 401 // the candidate is rejected at the mint, as a racing sync used to see

        val failure = r.vault.saveAndTest("new-key", 30, r.client::testKey, r.tokens::clear)

        assertEquals("Garmin didn't accept this key. Check you copied all of it.", failure)
        assertEquals("old-key", r.vault.key())
        assertEquals(before, r.vault.status.first()) // not Invalid, expiry unchanged
        assertTrue(before is KeyStatus.Ok)
        assertEquals(listOf("ApiKey new-key"), mintAuths())
    }

    @Test
    fun rejectedProfileCallDoesNotRetryWithTheStoredKey() = runBlocking {
        val r = rig()
        r.vault.save("old-key", 365)
        profileCode = 401

        assertTrue(r.client.testKey("new-key") {} is GarminResult.KeyInvalid)

        assertEquals(listOf("ApiKey new-key"), mintAuths()) // no authenticator re-mint with old-key
        assertTrue(r.vault.status.first() is KeyStatus.Ok)
    }

    @Test
    fun failedCandidateKeepsTheCachedJwtOfTheStoredKey() = runBlocking {
        val r = rig()
        r.vault.save("old-key", 365)
        r.tokens.token() // sync cached a JWT from old-key
        profileCode = 500

        val failure = r.vault.saveAndTest("new-key", 365, r.client::testKey, r.tokens::clear)

        assertEquals("Garmin is busy right now. Try again in a minute.", failure)
        assertEquals("old-key", r.vault.key())
        r.tokens.token() // still cached: no second mint with old-key
        assertEquals(listOf("ApiKey old-key", "ApiKey new-key"), mintAuths())
    }

    @Test
    fun successfulTestSavesTheKeyWithTheChosenLifetimeAndDropsTheOldToken() = runBlocking {
        val r = rig()
        r.vault.save("old-key", 365)
        r.tokens.token()
        profileCode = 200

        val failure = r.vault.saveAndTest("new-key", 30, r.client::testKey, r.tokens::clear)

        assertNull(failure)
        assertEquals("new-key", r.vault.key())
        assertEquals(KeyStatus.Ok(java.time.LocalDate.of(2026, 11, 23), 30), r.vault.status.first())
        r.tokens.token() // the cached JWT was cleared, so this mints with the new key
        assertEquals(listOf("ApiKey old-key", "ApiKey new-key", "ApiKey new-key"), mintAuths())
    }

    /** Every PROFILE answer takes [PROFILE_DELAY_MS]; [events] gets "mint" / "profile" at dispatch, [mintAt] the mint times. */
    private val events = ConcurrentLinkedQueue<String>()
    private val mintAt = ConcurrentLinkedQueue<Long>()
    private val profileAt = ConcurrentLinkedQueue<Long>()

    private fun slowProfile() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recorded.add(request)
                    return if (request.path == mintPath) {
                        events.add("mint")
                        mintAt.add(System.nanoTime())
                        MockResponse().setBody(jwt())
                    } else {
                        events.add("profile")
                        profileAt.add(System.nanoTime())
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"ok":1}""")
                            .setBodyDelay(PROFILE_DELAY_MS, TimeUnit.MILLISECONDS)
                    }
                }
            }
    }

    private suspend fun awaitEvent(name: String, count: Int) = withTimeout(5_000) { while (events.count { it == name } < count) delay(5) }

    @Test
    fun testKeyWaitsBehindAnInFlightRequest() = runBlocking {
        val r = rig()
        r.vault.save("old-key", 365)
        slowProfile()
        val sync = launch(Dispatchers.IO) { r.client.fetch(GarminEndpoint.PROFILE, "") }
        awaitEvent("profile", 1) // the sync's request is on the wire, its response still pending

        r.client.testKey("new-key") {}
        sync.join()

        assertEquals(listOf("mint", "profile", "mint", "profile"), events.toList())
        val (syncProfile, candidateMint) = profileAt.first() to mintAt.last()
        // Without the shared lock the candidate's mint would go out immediately, not after the sync's response.
        assertTrue(candidateMint - syncProfile >= PROFILE_DELAY_MS * 1_000_000 * 9 / 10)
    }

    @Test
    fun nothingWithTheStoredKeyRunsBetweenAPassingTestAndTheSave() = runBlocking {
        val r = rig()
        r.vault.save("old-key", 365)
        r.tokens.token() // a sync cached a JWT from old-key
        slowProfile()
        val test =
            launch(Dispatchers.IO) {
                r.client.testKey("new-key") {
                    delay(200)
                    events.add("saving")
                    r.vault.save("new-key", 365)
                }
            }
        awaitEvent("profile", 1) // the test holds the request lock
        val sync = launch(Dispatchers.IO) { r.client.fetch(GarminEndpoint.PROFILE, "") } // queued behind it
        test.join()
        sync.join()

        // The queued request only goes out after the new key is saved (its 401 could otherwise flag the new key).
        assertEquals(listOf("mint", "profile", "saving", "profile"), events.toList())
        assertEquals("new-key", r.vault.key())
    }

    @Test
    fun cancellationDuringOnValidStillSavesAndClearsTheToken() = runBlocking {
        val r = rig()
        r.vault.save("old-key", 365)
        var cleared = false
        // The screen goes away right after the test passes, before the save + clear pair runs.
        val test: dev.baseline.garmin.KeyTest = { _, onValid ->
            currentCoroutineContext().cancel()
            onValid()
            GarminResult.Empty
        }
        launch(Dispatchers.Default) {
            r.vault.saveAndTest("new-key", 365, test) {
                delay(50)
                cleared = true
            }
        }.join()

        assertEquals("new-key", r.vault.key())
        assertTrue(cleared)
    }

    @Test
    fun failedBodyReadAfter200DoesNotSaveTheKey() = runBlocking {
        val r = rig()
        r.vault.save("old-key", 365)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == mintPath) {
                    MockResponse().setBody(jwt())
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"ok":1}""")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
            }

        val failure = r.vault.saveAndTest("new-key", 365, r.client::testKey, r.tokens::clear)

        assertEquals("No connection. Check your internet and try again.", failure)
        assertEquals("old-key", r.vault.key()) // onValid only runs once the body is read
    }

    private companion object {
        const val PROFILE_DELAY_MS = 300L
    }
}
