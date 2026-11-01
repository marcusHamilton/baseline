package dev.baseline.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.baseline.testutil.ScopeCleanup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.GeneralSecurityException

class AiKeyVaultTest {
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private val cleanup = ScopeCleanup()

    private val xor: (ByteArray) -> ByteArray = { b -> ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() } }
    private val baseUrl = "https://openrouter.ai/api/v1".toHttpUrl()
    private val model = "deepseek/deepseek-v4.1-flash"

    @Before
    fun setUp() {
        file = File.createTempFile("aivault", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = cleanup.storeScope) { file }
    }

    @After
    fun tearDown() {
        runBlocking { cleanup.close() }
        file.delete()
    }

    private fun vault(enc: (ByteArray) -> ByteArray = { it }, dec: (ByteArray) -> ByteArray = { it }) = AiKeyVault(store, enc, dec)

    @Test
    fun missingWhenNothingSaved() = runBlocking {
        assertEquals(AiKeyStatus.Missing, vault().status.first())
        assertNull(vault().config())
    }

    @Test
    fun saveRoundTripsAndReportsStatus() = runBlocking {
        val v = vault()
        v.save(baseUrl, "sk-test", model, toolsSupported = true)
        assertEquals(AiConfig(baseUrl, "sk-test", model, true), v.config())
        assertEquals(AiKeyStatus.Set("openrouter.ai", model, true, invalid = false), v.status.first())
    }

    @Test
    fun keyIsEncryptedAtRest() = runBlocking {
        val v = vault(xor, xor)
        v.save(baseUrl, "sk-secret-123", model, toolsSupported = false)
        assertEquals("sk-secret-123", v.config()?.key)
        val stored = store.data.first().asMap().values.joinToString { it.toString() }
        assertFalse(stored.contains("sk-secret-123"))
        val raw = store.data.first()[stringPreferencesKey("ai_key")]!!
        assertFalse(java.util.Base64.getDecoder().decode(raw).decodeToString().contains("sk-secret-123"))
    }

    @Test
    fun markInvalidThenMarkValid() = runBlocking {
        val v = vault()
        v.save(baseUrl, "k", model, true)
        v.markInvalid()
        assertEquals(AiKeyStatus.Set("openrouter.ai", model, true, invalid = true), v.status.first())
        v.markValid()
        assertEquals(AiKeyStatus.Set("openrouter.ai", model, true, invalid = false), v.status.first())
    }

    @Test
    fun saveAfterMarkInvalidResetsInvalid() = runBlocking {
        val v = vault()
        v.save(baseUrl, "k", model, true)
        v.markInvalid()
        v.save(baseUrl, "k2", model, true)
        assertEquals(AiKeyStatus.Set("openrouter.ai", model, true, invalid = false), v.status.first())
    }

    @Test
    fun clearGivesMissing() = runBlocking {
        val v = vault()
        v.save(baseUrl, "k", model, true)
        v.markInvalid()
        v.clear()
        assertEquals(AiKeyStatus.Missing, v.status.first())
        assertNull(v.config())
        assertNull(store.data.first()[stringPreferencesKey("ai_model")])
    }

    @Test
    fun setModelKeepsKeyAndUrl() = runBlocking {
        val v = vault()
        v.save(baseUrl, "k", model, true)
        v.setModel("other/model", toolsSupported = false)
        assertEquals(AiConfig(baseUrl, "k", "other/model", false), v.config())
    }

    @Test
    fun undecryptableKeyResetsToMissing() = runBlocking {
        vault().save(baseUrl, "k", model, true)
        val v = vault(dec = { throw GeneralSecurityException("bad tag") })
        assertNull(v.config())
        assertEquals(AiKeyStatus.Missing, v.status.first())
    }

    @Test
    fun corruptBase64ResetsToMissing() = runBlocking {
        vault().save(baseUrl, "k", model, true)
        store.edit { it[stringPreferencesKey("ai_key")] = "!!not base64!!" }
        val v = vault()
        assertNull(v.config())
        assertEquals(AiKeyStatus.Missing, v.status.first())
    }

    @Test
    fun unparseableBaseUrlResetsToMissing() = runBlocking {
        vault().save(baseUrl, "k", model, true)
        store.edit { it[stringPreferencesKey("ai_base_url")] = "not a url" }
        val v = vault()
        assertNull(v.config())
        assertEquals(AiKeyStatus.Missing, v.status.first())
    }
}
