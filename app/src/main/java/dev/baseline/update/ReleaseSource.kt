package dev.baseline.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface LatestRelease {
    /** 304: the same answer as last time ([etag] matched). */
    data object NotModified : LatestRelease

    data class Found(val release: Release, val etag: String?) : LatestRelease

    data class Failed(val message: String) : LatestRelease
}

/**
 * Reads the latest GitHub release. [http] is a plain client with no Garmin authenticator: no Garmin credential may
 * ever be sent to GitHub. The API is public, so the request carries no token either.
 */
class ReleaseSource(private val http: OkHttpClient, private val url: String, private val userAgent: String) {
    suspend fun latest(etag: String?): LatestRelease = withContext(Dispatchers.IO) {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()
        try {
            http.newCall(request).execute().use { r ->
                when (r.code) {
                    304 -> LatestRelease.NotModified

                    200 -> when (val parsed = parseRelease(r.body.string())) {
                        is ParsedRelease.Ok -> LatestRelease.Found(parsed.release, r.header("ETag"))
                        is ParsedRelease.Unusable -> LatestRelease.Failed(parsed.reason)
                    }

                    403, 429 -> LatestRelease.Failed("GitHub is rate-limiting update checks; it'll try again later")

                    404 -> LatestRelease.Failed("No releases found")

                    else -> LatestRelease.Failed("GitHub answered ${r.code}")
                }
            }
        } catch (_: IOException) {
            LatestRelease.Failed("Couldn't reach GitHub")
        }
    }
}
