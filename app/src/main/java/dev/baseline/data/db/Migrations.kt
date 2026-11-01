package dev.baseline.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

/**
 * Version 2 to 3 adds `ai_insight`. The SQL is the `createSql` for that table in `schemas/.../3.json`; Room validates the
 * result against the schema, so edit both together. Without a registered migration the destructive fallback in
 * [AppDatabase.create] would drop every table and re-run the ~1,200-request backfill.
 */
val MIGRATION_2_3: Migration = Migration(2, 3) { connection ->
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `ai_insight` (`scope` TEXT NOT NULL, `scopeKey` TEXT NOT NULL, " +
            "`fingerprint` TEXT NOT NULL, `text` TEXT NOT NULL, `engine` TEXT NOT NULL, `model` TEXT, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`scope`, `scopeKey`))",
    )
}
