package dev.baseline.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.baseline.scores.StrainCalibration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class UserPrefsTest {
    private val file = File.createTempFile("prefs", ".preferences_pb").also { it.delete() }
    private val prefs = UserPrefs(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file })

    @Test
    fun defaults() = runBlocking {
        assertEquals(DEFAULT_BASELINE_ORDER, prefs.baselineOrder.first())
        assertEquals(emptySet<String>(), prefs.hiddenRows.first())
        assertEquals(SyncInterval.HOURLY, prefs.syncInterval.first())
        assertEquals(BanisterSet.MALE_FITTED, prefs.banister.first())
        assertNull(prefs.cachedJwt.first())
        assertNull(prefs.strainCalibration.first())
        assertNull(prefs.strainCalibratedOn.first())
        assertNull(prefs.lastSyncedAt.first())
    }

    @Test
    fun lastSyncedAtRoundTrips() = runBlocking {
        val at = java.time.Instant.parse("2026-10-22T14:41:07Z")
        prefs.setLastSyncedAt(at)
        assertEquals(at, prefs.lastSyncedAt.first())
    }

    @Test
    fun strainCalibrationRoundTrips() = runBlocking {
        prefs.setStrainCalibration(StrainCalibration(74.25, 3, provisional = true), java.time.LocalDate.of(2026, 11, 1))
        assertEquals(StrainCalibration(74.25, 3, true), prefs.strainCalibration.first())
        assertEquals(java.time.LocalDate.of(2026, 11, 1), prefs.strainCalibratedOn.first())
    }

    @Test
    fun settersRoundTrip() = runBlocking {
        prefs.setBaselineOrder(listOf("rhr", "hrv"))
        prefs.setHiddenRows(setOf("bb"))
        prefs.setSyncInterval(SyncInterval.MANUAL)
        prefs.setBanister(BanisterSet.FEMALE_FITTED)
        prefs.setCachedJwt(byteArrayOf(1, 2, 3))
        assertEquals(listOf("rhr", "hrv"), prefs.baselineOrder.first())
        assertEquals(setOf("bb"), prefs.hiddenRows.first())
        assertEquals(SyncInterval.MANUAL, prefs.syncInterval.first())
        assertEquals(BanisterSet.FEMALE_FITTED, prefs.banister.first())
        assertArrayEquals(byteArrayOf(1, 2, 3), prefs.cachedJwt.first())
        prefs.setCachedJwt(null)
        assertNull(prefs.cachedJwt.first())
        assertEquals(false, prefs.notificationsAsked.first())
        prefs.setNotificationsAsked()
        assertEquals(true, prefs.notificationsAsked.first())
    }
}
