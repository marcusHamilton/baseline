package dev.baseline.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkVerifierTest {
    private val sha = "ab".repeat(32)
    private val release = Release("v0.05", "", "https://x", "https://x/a.apk", 100, sha)
    private val installed = ApkFacts("dev.baseline", 4, "0.04", setOf("key"))
    private val good = ApkFacts("dev.baseline", 5, "0.05", setOf("key"))

    private fun verify(archive: ApkFacts? = good, size: Long = 100, hash: String = sha, rel: Release = release, ours: ApkFacts = installed) =
        verifyApk(rel, size, hash, archive, ours)

    @Test
    fun aMatchingNewerApkPasses() {
        assertNull(verify())
        // Digests compare case-insensitively.
        assertNull(verify(hash = sha.uppercase()))
    }

    @Test
    fun theDownloadMustBeExactlyTheReleasedBytes() {
        assertEquals("Download was incomplete. Try again.", verify(size = 99))
        assertEquals("Download didn't match the release checksum.", verify(hash = "cd".repeat(32)))
    }

    @Test
    fun withoutADigestTheOtherChecksStillRun() {
        val noDigest = release.copy(sha256 = null)
        assertNull(verify(rel = noDigest, hash = "cd".repeat(32)))
        assertEquals("Release 0.05 isn't signed with Baseline's key.", verify(rel = noDigest, archive = good.copy(signers = setOf("other"))))
    }

    @Test
    fun itMustBeANewerBaseline() {
        assertEquals("Downloaded file isn't an app Android can read.", verify(archive = null))
        assertEquals("Downloaded file isn't Baseline.", verify(archive = good.copy(packageName = "dev.other")))
        assertEquals("Release 0.05 isn't newer than this build (version code 4).", verify(archive = good.copy(versionCode = 4)))
    }

    @Test
    fun aDifferentKeyIsRefusedButAnUnknownOneIsLeftToAndroid() {
        assertEquals("Release 0.05 isn't signed with Baseline's key.", verify(archive = good.copy(signers = setOf("other"))))
        // Some OS versions report no signers for an archive; Android's own update check still refuses a different key.
        assertNull(verify(archive = good.copy(signers = null)))
    }

    @Test
    fun aRealBuildNeverTakesATestBuildButTestBuildsUpdateEachOther() {
        assertEquals("Release 0.05 is a test build; it can't replace this one.", verify(archive = good.copy(versionName = "0.05-fake")))
        assertNull(verify(archive = good.copy(versionName = "0.05-fake"), ours = installed.copy(versionName = "0.04-fake")))
    }
}
