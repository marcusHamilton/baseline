package dev.baseline.sync

import android.content.Context
import androidx.work.impl.WorkManagerImpl

/** WorkManager's own database is never closed by the test helpers and logs a CloseGuard trace; reflection because Room 2 isn't on the classpath. */
fun closeWorkManagerDb(context: Context) {
    val db = WorkManagerImpl.getInstance(context).workDatabase
    db.javaClass.getMethod("close").invoke(db)
}
