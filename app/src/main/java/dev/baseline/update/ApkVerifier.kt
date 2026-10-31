package dev.baseline.update

/** What the app knows about an APK: from the downloaded archive, or from the installed package. [signers] are SHA-256 hex of each signing certificate; null if the OS didn't say. */
data class ApkFacts(val packageName: String, val versionCode: Long, val versionName: String?, val signers: Set<String>?)

/**
 * The checks between a finished download and the installer (spec: in-app updates §5). Android refuses an update with a
 * different key or a lower versionCode anyway; these give a clear message first and stop a "-fake" test build reaching a
 * real install. Returns null when the APK may be installed, else the message for the Failed state.
 */
fun verifyApk(release: Release, downloadedSize: Long, downloadedSha256: String, archive: ApkFacts?, installed: ApkFacts): String? {
    if (downloadedSize != release.apkSize) return "Download was incomplete. Try again."
    if (release.sha256 != null && !downloadedSha256.equals(release.sha256, ignoreCase = true)) {
        return "Download didn't match the release checksum."
    }
    if (archive == null) return "Downloaded file isn't an app Android can read."
    if (archive.packageName != installed.packageName) return "Downloaded file isn't Baseline."
    if (archive.versionCode <= installed.versionCode) return "Release ${release.version} isn't newer than this build (version code ${archive.versionCode})."
    if (archive.versionName.isFake() && !installed.versionName.isFake()) return "Release ${release.version} is a test build; it can't replace this one."
    val theirs = archive.signers
    val ours = installed.signers
    if (!theirs.isNullOrEmpty() && !ours.isNullOrEmpty() && theirs != ours) return "Release ${release.version} isn't signed with Baseline's key."
    return null
}

/** Builds made with -PgarminBaseUrl or -PupdatesUrl end in "-fake" (app/build.gradle.kts). */
private fun String?.isFake() = this?.endsWith("-fake") == true
