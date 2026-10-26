package dev.baseline.garmin

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** On a 401, re-mints the JWT once and retries; a second 401 for the same call is passed through. */
class GarminAuthenticator(private val tokens: TokenProvider) : Authenticator {
    // Runs on an OkHttp worker thread, so blocking is fine. A KeyInvalidException propagates to GarminClient.fetch.
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val jwt = runBlocking { tokens.forceRefresh() }
        return response.request.newBuilder().header("Authorization", "Bearer $jwt").build()
    }
}
