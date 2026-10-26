package dev.baseline.fit

import androidx.room3.withWriteTransaction
import com.garmin.fit.FitRuntimeException
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.AppDatabase
import dev.baseline.data.db.LapEntity
import dev.baseline.data.db.StrengthSetEntity
import dev.baseline.data.db.SwimLengthEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The sync engine's FIT hook: keeps the original file under `filesDir/fit/{id}.fit` (stored relative as `fit/{id}.fit`: resolve against filesDir when reading) and replaces the activity's
 * stream, laps, swim lengths and strength sets in one transaction. Re-running for the same id is idempotent.
 * IOException (disk) propagates so the task stays pending; undecodable input is an IllegalArgumentException.
 */
class FitIngest(private val filesDir: File, private val db: AppDatabase) {
    suspend fun ingest(id: Long, zip: ByteArray): Unit = withContext(Dispatchers.IO) {
        val bytes = FitReader.readZip(zip)
        val relative = "fit/$id.fit"
        val file = File(filesDir, relative)
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        val fit =
            try {
                FitReader.read(bytes)
            } catch (e: FitRuntimeException) {
                throw IllegalArgumentException("FIT $id: ${e.message}", e)
            }

        db.withWriteTransaction {
            val activity = db.activityDao().get(id) ?: return@withWriteTransaction // the FK needs the row
            db.activityStreamDao().upsert(ActivityStreamEntity(id, fit.streams.pack()))
            db.lapDao().deleteFor(id)
            db.lapDao().insertAll(
                fit.laps.mapIndexed { i, l ->
                    LapEntity(
                        activityId = id,
                        lapIndex = i,
                        startOffsetSec = l.startSec,
                        durationSec = l.durationSec,
                        distanceM = l.distanceM,
                        avgHr = l.avgHr?.toDouble(),
                        maxHr = l.maxHr?.toDouble(),
                        avgSpeed = l.avgSpeedMs,
                        avgPower = l.avgPowerW,
                    )
                },
            )
            db.swimLengthDao().deleteFor(id)
            db.swimLengthDao().insertAll(
                fit.lengths.mapIndexed { i, l ->
                    SwimLengthEntity(
                        activityId = id,
                        lengthIndex = i,
                        startOffsetSec = l.startSec,
                        active = l.active,
                        durationSec = l.durationSec,
                        strokeType = l.stroke,
                        strokeCount = l.strokes,
                    )
                },
            )
            db.strengthSetDao().deleteFor(id)
            db.strengthSetDao().insertAll(
                fit.sets.mapIndexed { i, s ->
                    StrengthSetEntity(
                        activityId = id,
                        setIndex = i,
                        startOffsetSec = s.startSec,
                        exerciseName = null,
                        setType = if (s.active) "ACTIVE" else "REST",
                        category = s.category,
                        reps = s.reps,
                        weightKg = s.weightKg,
                        durationSec = s.durationSec,
                    )
                },
            )
            db.activityDao().upsert(
                activity.copy(fitPath = relative, poolLengthM = activity.poolLengthM ?: fit.poolLengthM),
            )
        }
    }
}
