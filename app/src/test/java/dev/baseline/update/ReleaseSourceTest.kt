package dev.baseline.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReleaseSourceTest {
    private lateinit var server: MockWebServer
    private val latest = checkNotNull(javaClass.getResource("/fixtures/github_release_latest.json")).readText()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source() = ReleaseSource(OkHttpClient(), server.url("/repos/o/r/releases/latest").toString(), "Baseline/0.04")

    @Test
    fun aReleaseComesBackWithItsEtag() = runBlocking {
        server.enqueue(MockResponse().setBody(latest).setHeader("ETag", "\"abc\""))
        val found = source().latest(null) as LatestRelease.Found
        assertEquals("v0.04", found.release.tag)
        assertEquals("\"abc\"", found.etag)
        val request = server.takeRequest()
        assertEquals("Baseline/0.04", request.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertNull(request.getHeader("If-None-Match"))
        // The API is public: no token, and nothing of Garmin's, ever goes to GitHub.
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun theStoredEtagIsSentAndA304IsNotModified() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(LatestRelease.NotModified, source().latest("\"abc\""))
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun rateLimitsAndErrorsAreReadableFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue((source().latest(null) as LatestRelease.Failed).message.contains("rate-limiting"))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(LatestRelease.Failed("No releases found"), source().latest(null))
        server.enqueue(MockResponse().setResponseCode(502))
        assertEquals(LatestRelease.Failed("GitHub answered 502"), source().latest(null))
        server.enqueue(MockResponse().setBody("""{"tag_name":"v0.05","assets":[]}"""))
        assertEquals(LatestRelease.Failed("Release v0.05 has 0 APK files, not 1"), source().latest(null))
    }

    @Test
    fun noNetworkIsAFailureNotACrash() = runBlocking {
        val url = server.url("/x").toString()
        server.shutdown()
        assertEquals(LatestRelease.Failed("Couldn't reach GitHub"), ReleaseSource(OkHttpClient(), url, "Baseline/0.04").latest(null))
    }
}
