package dev.baseline.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate

/**
 * `AppDatabase` falls back to a destructive rebuild when no migration matches, which would wipe the synced data and
 * replan the ~1,200-request backfill. These tests build a real version-2 file from the exported schema and open it
 * the way the app does.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    /** Creates [name] exactly as Room 3 left it at version 2, from `schemas/.../2.json`, with one row in three tables. */
    private fun createVersion2Database() {
        val schema = JSONObject(File("schemas/dev.baseline.data.db.AppDatabase/2.json").readText()).getJSONObject("database")
        assertEquals(2, schema.getInt("version"))
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    raw.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.execSQL("INSERT INTO day (date, restingHr) VALUES ('2026-10-30', 52)")
            raw.execSQL(
                "INSERT INTO score (date, recovery, recoveryPartial, dayLoad, strain, calibrationVersion) " +
                    "VALUES ('2026-10-30', 71, 0, 120.5, 12.3, 1)",
            )
            raw.execSQL(
                "INSERT INTO activity (id, typeKey, sportFamily, startTimeLocal, day, startGmt, durationSec) " +
                    "VALUES (42, 'running', 'RUN', '2026-10-30T07:00', '2026-10-30', 1793343600000, 1800.0)",
            )
            raw.version = 2
        } finally {
            raw.close()
        }
    }

    @Test
    fun upgradeKeepsData() = runBlocking {
        createVersion2Database()

        val db = AppDatabase.create(context, name)
        try {
            val date = LocalDate.parse("2026-10-30")
            assertEquals(52, db.dayDao().get(date)?.restingHr)
            assertEquals(71, db.scoreDao().get(date)?.recovery)
            val activity = db.activityDao().get(42)
            assertNotNull(activity)
            assertEquals("running", activity!!.typeKey)
            assertEquals(1800.0, activity.durationSec, 0.0)
            assertNull(db.aiInsightDao().get("today", "2026-11-01"))
        } finally {
            db.close()
        }
    }
}
