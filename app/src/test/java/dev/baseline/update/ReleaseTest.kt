package dev.baseline.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val latest = checkNotNull(javaClass.getResource("/fixtures/github_release_latest.json")).readText()

    @Test
    fun parsesTheRealLatestReleaseResponse() {
        val r = (parseRelease(latest) as ParsedRelease.Ok).release
        assertEquals("v0.04", r.tag)
        assertEquals("0.04", r.version)
        assertEquals("https://github.com/marcusHamilton/baseline/releases/tag/v0.04", r.pageUrl)
        assertEquals("https://github.com/marcusHamilton/baseline/releases/download/v0.04/Baseline-0.04.apk", r.apkUrl)
        assertEquals(54815418L, r.apkSize)
        assertEquals("80430eaf23dd74f890c9b95c9d7c31486f02259747e3bc23e4d51303bfe26fff", r.sha256)
        assertTrue(r.notes.startsWith("Adds five home-screen widgets"))
    }

    @Test
    fun picksTheOneApkWhateverItIsCalled() {
        // v0.02 and v0.03 named it app-release.apk; other assets (a mapping file) don't count.
        val body = release(asset("app-release.apk"), asset("mapping.txt"))
        assertEquals("https://x/app-release.apk", (parseRelease(body) as ParsedRelease.Ok).release.apkUrl)
    }

    @Test
    fun zeroOrSeveralApksAreUnusable() {
        assertEquals(ParsedRelease.Unusable("Release v0.05 has 0 APK files, not 1"), parseRelease(release()))
        assertEquals(ParsedRelease.Unusable("Release v0.05 has 2 APK files, not 1"), parseRelease(release(asset("a.apk"), asset("b.apk"))))
    }

    @Test
    fun draftsPreReleasesAndJunkAreUnusable() {
        assertTrue(parseRelease(release(asset("a.apk"), extra = """"prerelease": true,""")) is ParsedRelease.Unusable)
        assertTrue(parseRelease(release(asset("a.apk"), extra = """"draft": true,""")) is ParsedRelease.Unusable)
        assertTrue(parseRelease("<html>rate limited</html>") is ParsedRelease.Unusable)
        assertTrue(parseRelease("""{"message":"Not Found"}""") is ParsedRelease.Unusable)
    }

    @Test
    fun aMissingOrMalformedDigestIsNull() {
        assertNull((parseRelease(release(asset("a.apk", digest = null))) as ParsedRelease.Ok).release.sha256)
        assertNull((parseRelease(release(asset("a.apk", digest = "md5:abc"))) as ParsedRelease.Ok).release.sha256)
    }

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(isNewer("v0.05", "0.04"))
        assertTrue(isNewer("v0.10", "0.09"))
        assertTrue(isNewer("v1.0", "0.99"))
        assertTrue(isNewer("v0.04.1", "0.04"))
        assertFalse(isNewer("v0.04", "0.04"))
        assertFalse(isNewer("v0.03", "0.04"))
        // A test build's suffix is ignored; the tag decides.
        assertTrue(isNewer("v0.05", "0.04-fake"))
        assertFalse(isNewer("v0.04", "0.04-fake"))
    }

    @Test
    fun unparseableVersionsAreNeverNewer() {
        assertFalse(isNewer("nightly", "0.04"))
        assertFalse(isNewer("v0.05", "dev"))
        assertFalse(isNewer("v0..5", "0.04"))
        assertFalse(isNewer("", "0.04"))
    }

    @Test
    fun notesLoseTheirMarkdown() {
        val md = "## What's new\r\n\r\n- **Faster** start-up\n* See [the spec](https://x/spec)\n\n\n\nUse `Sync now`."
        assertEquals("What's new\n\n• Faster start-up\n• See the spec\n\nUse Sync now.", plainNotes(md))
    }

    private fun asset(name: String, digest: String? = "sha256:" + "ab".repeat(32)) =
        """{"name":"$name","size":10,${digest?.let { "\"digest\":\"$it\"," } ?: ""}"browser_download_url":"https://x/$name"}"""

    private fun release(vararg assets: String, extra: String = "") =
        """{"tag_name":"v0.05",$extra"html_url":"https://x/v0.05","body":"Notes","assets":[${assets.joinToString(",")}]}"""
}
