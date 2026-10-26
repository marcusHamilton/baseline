package dev.baseline.data.db

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase

/**
 * Cache of re-downloadable Garmin data: bump the version on every schema change; the destructive fallback rebuilds it and the backfill replans.
 */
@Database(
    entities = [
        RawResponse::class, SyncTaskEntity::class, DayEntity::class, HrDayEntity::class, BodyBatteryDayEntity::class,
        ActivityEntity::class, ActivityStreamEntity::class, LapEntity::class, SwimLengthEntity::class,
        StrengthSetEntity::class, ScoreEntity::class, ActivityScoreEntity::class, BestEffortEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawDao(): RawDao

    abstract fun taskDao(): TaskDao

    abstract fun dayDao(): DayDao

    abstract fun hrDayDao(): HrDayDao

    abstract fun bodyBatteryDayDao(): BodyBatteryDayDao

    abstract fun activityDao(): ActivityDao

    abstract fun activityStreamDao(): ActivityStreamDao

    abstract fun lapDao(): LapDao

    abstract fun swimLengthDao(): SwimLengthDao

    abstract fun strengthSetDao(): StrengthSetDao

    abstract fun scoreDao(): ScoreDao

    abstract fun activityScoreDao(): ActivityScoreDao

    abstract fun bestEffortDao(): BestEffortDao

    companion object {
        fun create(context: Context, name: String = "baseline.db"): AppDatabase =
            // Room 3 has no reflection: the KSP-generated AppDatabase_Impl is passed as the factory.
            Room
                .databaseBuilder<AppDatabase>(context.applicationContext, name) { AppDatabase_Impl() }
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
