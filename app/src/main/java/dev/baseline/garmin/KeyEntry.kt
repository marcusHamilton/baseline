package dev.baseline.garmin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/** Tests a candidate key and calls the callback, still inside the request lock, if it passes (`GarminClient.testKey`). */
typealias KeyTest = suspend (String, suspend () -> Unit) -> GarminResult

/**
 * The test-then-save flow shared by onboarding and Settings. [testKey] tests the candidate without storing it, so a failed
 * test (or a screen that goes away mid-test) leaves the vault exactly as it was and a running sync keeps its key. Only
 * success saves the key and then calls [clearToken] (so the next sync mints with it), both inside [testKey]'s request lock. Returns null on success, else a message
 * for the user. Messages never include the key.
 */
suspend fun ApiKeyVault.saveAndTest(key: String, lifetimeDays: Int, testKey: KeyTest, clearToken: suspend () -> Unit): String? = try {
    when (
        val r =
            testKey(key) {
                withContext(NonCancellable) {
                    save(key, lifetimeDays)
                    clearToken()
                }
            }
    ) {
        is GarminResult.Ok, GarminResult.Empty -> null
        GarminResult.KeyInvalid -> "Garmin didn't accept this key. Check you copied all of it."
        is GarminResult.RateLimited -> "Garmin is busy right now. Try again in a minute."
        is GarminResult.BadRequest -> "Couldn't reach Garmin (code 400)."
        is GarminResult.Failed -> "Couldn't reach Garmin (code ${r.code ?: "unknown"})."
    }
} catch (_: IOException) {
    "No connection. Check your internet and try again."
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    "Couldn't reach Garmin (code unknown)."
}
