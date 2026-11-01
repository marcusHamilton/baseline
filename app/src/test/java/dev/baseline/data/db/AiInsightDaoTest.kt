package dev.baseline.data.db

import dev.baseline.testutil.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AiInsightDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private fun row(scope: String = "today", key: String = "2026-11-01", text: String = "Rested.", model: String? = "m") =
        AiInsightEntity(scope, key, "fp-$text", text, "cloud", model, Instant.ofEpochMilli(1_000))

    @Test
    fun putThenGetRoundTrips() = runBlocking {
        val dao = db.aiInsightDao()
        dao.put(row(model = null))
        assertEquals(row(model = null), dao.get("today", "2026-11-01"))
    }

    @Test
    fun getMissesOnOtherScopeOrKey() = runBlocking {
        val dao = db.aiInsightDao()
        dao.put(row())
        assertNull(dao.get("sleep", "2026-11-01"))
        assertNull(dao.get("today", "2026-11-02"))
    }

    @Test
    fun putWithTheSameKeyReplaces() = runBlocking {
        val dao = db.aiInsightDao()
        dao.put(row(text = "First."))
        dao.put(row(text = "Second."))
        assertEquals("Second.", dao.get("today", "2026-11-01")?.text)
        assertEquals("fp-Second.", dao.get("today", "2026-11-01")?.fingerprint)
    }

    @Test
    fun clearDeletesEveryRow() = runBlocking {
        val dao = db.aiInsightDao()
        dao.put(row())
        dao.put(row(scope = "activity", key = "42"))
        dao.clear()
        assertNull(dao.get("today", "2026-11-01"))
        assertNull(dao.get("activity", "42"))
    }
}
