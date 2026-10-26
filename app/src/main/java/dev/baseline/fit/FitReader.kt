package dev.baseline.fit

import com.garmin.fit.LapMesg
import com.garmin.fit.LapMesgListener
import com.garmin.fit.LengthMesg
import com.garmin.fit.LengthMesgListener
import com.garmin.fit.LengthType
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.SetMesg
import com.garmin.fit.SetMesgListener
import com.garmin.fit.SetType
import com.garmin.fit.SwimStroke
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Per-record streams, one entry per FIT Record message (not resampled; gaps can be > 1 s).
 *
 * Missing samples carry a sentinel and never NaN: [hr], [powerW] and [cadence] use -1; [distanceM], [speedMs],
 * [altitudeM], [lat] and [lon] use [MISSING] (-1 would be a legal altitude or coordinate). An optional array is
 * null when no record has that field at all.
 */
class ActivityStreams(
    /** Unix epoch seconds. */
    val epochSec: LongArray,
    val hr: IntArray,
    val distanceM: FloatArray?,
    val speedMs: FloatArray?,
    val powerW: IntArray?,
    val altitudeM: FloatArray?,
    /** Degrees. */
    val lat: DoubleArray?,
    val lon: DoubleArray?,
    val cadence: IntArray?,
) {
    /** Layout: version byte, count, presence bitmask, then the present arrays in constructor order. */
    fun pack(): ByteArray {
        val n = epochSec.size
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(VERSION)
            o.writeInt(n)
            val optional = listOf(distanceM, speedMs, powerW, altitudeM, lat, lon, cadence)
            o.writeByte(optional.foldIndexed(0) { i, m, a -> if (a != null) m or (1 shl i) else m })
            epochSec.forEach(o::writeLong)
            hr.forEach(o::writeInt)
            distanceM?.forEach(o::writeFloat)
            speedMs?.forEach(o::writeFloat)
            powerW?.forEach(o::writeInt)
            altitudeM?.forEach(o::writeFloat)
            lat?.forEach(o::writeDouble)
            lon?.forEach(o::writeDouble)
            cadence?.forEach(o::writeInt)
        }
        return out.toByteArray()
    }

    companion object {
        const val MISSING = -1e9f
        private const val VERSION = 1

        fun unpack(b: ByteArray): ActivityStreams = try {
            DataInputStream(ByteArrayInputStream(b)).use { i ->
                require(i.readByte().toInt() == VERSION) { "unknown stream version" }
                val n = i.readInt()
                val mask = i.readByte().toInt()
                require(n >= 0 && n <= b.size) { "bad stream length" }
                fun has(bit: Int) = mask and (1 shl bit) != 0
                ActivityStreams(
                    LongArray(n) { i.readLong() },
                    IntArray(n) { i.readInt() },
                    if (has(0)) FloatArray(n) { i.readFloat() } else null,
                    if (has(1)) FloatArray(n) { i.readFloat() } else null,
                    if (has(2)) IntArray(n) { i.readInt() } else null,
                    if (has(3)) FloatArray(n) { i.readFloat() } else null,
                    if (has(4)) DoubleArray(n) { i.readDouble() } else null,
                    if (has(5)) DoubleArray(n) { i.readDouble() } else null,
                    if (has(6)) IntArray(n) { i.readInt() } else null,
                )
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("truncated stream blob", e)
        }
    }
}

/** [startSec] is seconds from the activity start on all of Lap, SwimLength and StrengthSet. */
data class Lap(
    val startSec: Double,
    val durationSec: Double,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int? = null,
    val avgSpeedMs: Double? = null,
    val avgPowerW: Double? = null,
)

/** [stroke] is the lowercase FIT swim_stroke name (e.g. "freestyle"); null for rest lengths. */
data class SwimLength(val startSec: Double, val durationSec: Double, val strokes: Int?, val stroke: String?, val active: Boolean)

data class StrengthSet(
    val startSec: Double,
    val durationSec: Double,
    val reps: Int?,
    val weightKg: Double?,
    val category: Int?,
    val active: Boolean,
)

data class FitActivity(
    val streams: ActivityStreams,
    val laps: List<Lap>,
    val lengths: List<SwimLength>,
    val sets: List<StrengthSet>,
    val poolLengthM: Double?,
    val startGmt: Instant,
    val durationSec: Double,
)

object FitReader {
    /** The first `.fit` entry. A damaged zip is a bad payload (IllegalArgumentException), not a network error. */
    fun readZip(zip: ByteArray): ByteArray = try {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith(".fit", ignoreCase = true) }
                ?.let { z.readBytes() }
        }
    } catch (e: IOException) {
        // ZipInputStream raises ZipException / EOFException for damage; a ByteArray source has no real I/O to fail.
        throw IllegalArgumentException("corrupt zip: ${e.message}", e)
    } ?: throw IllegalArgumentException("zip has no .fit entry")

    /** Throws [com.garmin.fit.FitRuntimeException] on undecodable input and IllegalArgumentException if it has no records. */
    fun read(fit: ByteArray): FitActivity {
        val records = mutableListOf<RecordMesg>()
        val laps = mutableListOf<LapMesg>()
        val lengths = mutableListOf<LengthMesg>()
        val sets = mutableListOf<SetMesg>()
        val sessions = mutableListOf<SessionMesg>()
        MesgBroadcaster().apply {
            addListener(RecordMesgListener { if (it.timestamp != null) records += it })
            addListener(LapMesgListener { laps += it })
            addListener(LengthMesgListener { lengths += it })
            addListener(SetMesgListener { sets += it })
            addListener(SessionMesgListener { sessions += it })
            run(ByteArrayInputStream(fit))
        }
        require(records.isNotEmpty()) { "FIT file has no records" }

        val start = (sessions.firstNotNullOfOrNull { it.startTime } ?: records.first().timestamp).instant
        fun off(t: com.garmin.fit.DateTime?) = t?.let { (it.instant.toEpochMilli() - start.toEpochMilli()) / 1000.0 } ?: 0.0
        val n = records.size

        return FitActivity(
            streams = ActivityStreams(
                epochSec = LongArray(n) { records[it].timestamp.instant.epochSecond },
                hr = ints(records) { it.heartRate?.toInt() } ?: IntArray(n) { -1 },
                distanceM = floats(records) { it.distance },
                speedMs = floats(records) { it.enhancedSpeed ?: it.speed },
                powerW = ints(records) { it.power },
                altitudeM = floats(records) { it.enhancedAltitude ?: it.altitude },
                lat = degrees(records) { it.positionLat },
                lon = degrees(records) { it.positionLong },
                cadence = ints(records) { it.cadence?.toInt() },
            ),
            laps = laps.map {
                Lap(
                    off(it.startTime), (it.totalElapsedTime ?: it.totalTimerTime ?: 0f).toDouble(),
                    it.totalDistance?.toDouble(), it.avgHeartRate?.toInt(), it.maxHeartRate?.toInt(),
                    (it.enhancedAvgSpeed ?: it.avgSpeed)?.toDouble(), it.avgPower?.toDouble(),
                )
            },
            lengths = lengths.map {
                SwimLength(
                    off(it.startTime), (it.totalElapsedTime ?: it.totalTimerTime ?: 0f).toDouble(), it.totalStrokes,
                    it.swimStroke?.let(SwimStroke::getStringFromValue)?.lowercase(), it.lengthType == LengthType.ACTIVE,
                )
            },
            sets = sets.map {
                StrengthSet(
                    off(it.startTime), (it.duration ?: 0f).toDouble(), it.repetitions, it.weight?.toDouble(),
                    it.category?.firstNotNullOfOrNull { c -> c }, it.setType == SetType.ACTIVE,
                )
            },
            poolLengthM = sessions.firstNotNullOfOrNull { it.poolLength }?.toDouble(),
            startGmt = start,
            durationSec = sessions.mapNotNull { it.totalElapsedTime }.sumOf { it.toDouble() }.takeIf { it > 0 }
                ?: (records.last().timestamp.timestamp - records.first().timestamp.timestamp).toDouble(),
        )
    }

    private fun ints(r: List<RecordMesg>, f: (RecordMesg) -> Int?): IntArray? {
        val v = r.map(f)
        return if (v.all { it == null }) null else IntArray(v.size) { v[it] ?: -1 }
    }

    private fun floats(r: List<RecordMesg>, f: (RecordMesg) -> Float?): FloatArray? {
        val v = r.map { f(it)?.takeUnless(Float::isNaN) }
        return if (v.all { it == null }) null else FloatArray(v.size) { v[it] ?: ActivityStreams.MISSING }
    }

    /** Semicircles to degrees; one array per axis, so a record with only one axis present still gets MISSING on the other. */
    private fun degrees(r: List<RecordMesg>, f: (RecordMesg) -> Int?): DoubleArray? {
        val v = r.map(f)
        return if (v.all { it == null }) null
        else DoubleArray(v.size) { v[it]?.let { s -> s.toDouble() * 180.0 / 2147483648.0 } ?: ActivityStreams.MISSING.toDouble() }
    }
}
