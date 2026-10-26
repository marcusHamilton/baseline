package dev.baseline.garmin

import dev.baseline.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class GarminBaseUrlTest {
    /** -PgarminBaseUrl (tools/fake_garmin.py) is for emulator builds; without it the app must talk to Garmin itself. */
    @Test
    fun defaultBuildUsesTheRealApi() {
        assertEquals("https://personal.connectapi.garmin.com", BuildConfig.GARMIN_BASE_URL)
    }
}
