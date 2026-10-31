package dev.baseline.testutil

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.AppDatabase_Impl
import kotlinx.coroutines.runBlocking

/**
 * An in-memory [AppDatabase] that is already open. Build test databases with this, not `Room.inMemoryDatabaseBuilder`.
 *
 * Room 3.0.3 marks the database configured (schema created) before the new connection reaches its pool, and the hand-off
 * passes through a `withContext` that throws if the caller was cancelled meanwhile. A first open cancelled in that gap (a
 * ViewModel's `flatMapLatest` switching, a scope cleared) drops the connection; the next query opens a fresh `:memory:`
 * database that Room no longer sets up, so every later query fails with "no such table". A file database just reopens
 * its file, so only tests are hit. Opening here, outside any test coroutine, leaves no first open to cancel.
 */
fun inMemoryDatabase(context: Context = ApplicationProvider.getApplicationContext()): AppDatabase =
    Room.inMemoryDatabaseBuilder<AppDatabase>(context) { AppDatabase_Impl() }.allowMainThreadQueries().build()
        .also { db -> runBlocking { db.useWriterConnection {} } }
