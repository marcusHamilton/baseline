package dev.baseline.garmin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ApiKeyVaultTest {
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    private var today = LocalDate.of(2026, 10, 24)

    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = today.atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    private val xor: (ByteArray) -> ByteArray = { b -> ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() } }

    @Before
    fun setUp() {
        file = File.createTempFile("vault", ".preferences_pb").also { it.delete() }
        scope = CoroutineScope(Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun vault(enc: (ByteArray) -> ByteArray = { it }, dec: (ByteArray) -> ByteArray = { it }) =
        ApiKeyVault(store, enc, dec, clock)

    @Test
    fun missingWhenNoKey() = runBlocking {
        assertEquals(KeyStatus.Missing, vault().status.first())
        assertNull(vault().key())
    }

    @Test
    fun statusTracksExpiry() = runBlocking {
        val v = vault()
        v.save("k", 365)
        val expires = LocalDate.of(2027, 10, 24)
        assertEquals(KeyStatus.Ok(expires, 365), v.status.first())
        today = LocalDate.of(2027, 10, 18)
        assertEquals(KeyStatus.ExpiringSoon(expires, 6), v.status.first())
        today = LocalDate.of(2027, 10, 17)
        assertEquals(KeyStatus.ExpiringSoon(expires, 7), v.status.first())
        today = LocalDate.of(2027, 10, 16)
        assertEquals(KeyStatus.Ok(expires, 8), v.status.first())
        today = LocalDate.of(2027, 10, 24)
        assertEquals(KeyStatus.ExpiringSoon(expires, 0), v.status.first())
        today = LocalDate.of(2027, 10, 25)
        assertEquals(KeyStatus.Expired(expires), v.status.first())
    }

    @Test
    fun statusFollowsTheDateFlowWithoutAPrefsWrite() = runBlocking {
        val date = MutableStateFlow(LocalDate.of(2027, 10, 24))
        val v = ApiKeyVault(store, { it }, { it }, clock, date)
        v.save("k", 365) // expires 2027-10-24 (clock is 2026-10-24)
        val expires = LocalDate.of(2027, 10, 24)
        assertEquals(KeyStatus.ExpiringSoon(expires, 0), v.status.first())
        val seen = async { v.status.first { it is KeyStatus.Expired } }
        date.value = LocalDate.of(2027, 10, 25)
        assertEquals(KeyStatus.Expired(expires), withTimeout(5_000) { seen.await() })
    }

    @Test
    fun invalidOverridesExpiryAndSaveClearsIt() = runBlocking {
        val v = vault()
        v.save("k", 30)
        v.markInvalid()
        assertEquals(KeyStatus.Invalid, v.status.first())
        v.save("k2", 30)
        assertEquals(KeyStatus.Ok(LocalDate.of(2026, 11, 23), 30), v.status.first())
    }

    @Test
    fun clearRemovesKey() = runBlocking {
        val v = vault()
        v.save("k", 90)
        v.clear()
        assertNull(v.key())
        assertEquals(KeyStatus.Missing, v.status.first())
    }

    @Test
    fun keyRoundTripsAndIsEncryptedAtRest() = runBlocking {
        val v = vault(xor, xor)
        v.save("secret-key-123", 90)
        assertEquals("secret-key-123", v.key())
        val stored = store.data.first().asMap().values.joinToString { it.toString() }
        assertFalse(stored.contains("secret-key-123"))
        val raw = store.data.first()[stringPreferencesKey("api_key_enc")]!!
        assertFalse(java.util.Base64.getDecoder().decode(raw).decodeToString().contains("secret-key-123"))
    }

    @Test
    fun undecryptableKeyResetsToMissing() = runBlocking {
        vault().save("k", 90)
        val v = vault(dec = { throw java.security.GeneralSecurityException("bad tag") })
        assertNull(v.key())
        assertEquals(KeyStatus.Missing, v.status.first())
    }

    @Test
    fun corruptBase64ResetsToMissing() = runBlocking {
        store.edit {
            it[stringPreferencesKey("api_key_enc")] = "!!not base64!!"
            it[stringPreferencesKey("api_key_expires")] = "2027-10-24"
        }
        val v = vault()
        assertNull(v.key())
        assertEquals(KeyStatus.Missing, v.status.first())
    }
}
