package dev.baseline.data.db

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** A DB file from an older schema (different version and hash) must be rebuilt empty, not crash. Uses its own file, never baseline.db. */
@RunWith(AndroidJUnit4::class)
class CacheRebuildTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "cache_test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun schemaMismatchRebuildsCache() = runBlocking {
        val old = object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: SQLiteDatabase) = db.execSQL("CREATE TABLE junk (x INTEGER)")
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = Unit
        }
        old.writableDatabase.use { it.execSQL("INSERT INTO junk VALUES (1)") }
        old.close()

        val db = AppDatabase.create(context, name)
        try {
            assertNull(db.dayDao().get(LocalDate.of(2026, 10, 23)))
            assertEquals(0, db.taskDao().count())
            assertNull(db.bodyBatteryDayDao().get(LocalDate.of(2026, 10, 23)))
        } finally {
            db.close()
        }
    }
}
