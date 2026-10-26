package dev.baseline.data.db

import androidx.room3.ColumnTypeConverter
import dev.baseline.scores.HrPoint
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Enums use Room's built-in name-string mapping; dates are ISO strings so they sort and range-query correctly. */
class Converters {
    @ColumnTypeConverter fun dateToString(d: LocalDate?): String? = d?.toString()
    @ColumnTypeConverter fun stringToDate(s: String?): LocalDate? = s?.let(LocalDate::parse)
    @ColumnTypeConverter fun dateTimeToString(d: LocalDateTime?): String? = d?.toString()
    @ColumnTypeConverter fun stringToDateTime(s: String?): LocalDateTime? = s?.let(LocalDateTime::parse)
    @ColumnTypeConverter fun instantToMillis(i: Instant?): Long? = i?.toEpochMilli()
    @ColumnTypeConverter fun millisToInstant(m: Long?): Instant? = m?.let(Instant::ofEpochMilli)
}

/**
 * Layout: 8-byte big-endian epochSec of the first sample, then 5-byte records of
 * (Int seconds since that first sample, unsigned byte bpm). Null samples are dropped.
 */
object HrPacking {
    private const val HEADER = 8
    private const val RECORD = 5

    fun pack(samples: List<HrPoint?>): ByteArray {
        val pts = samples.filterNotNull()
        if (pts.isEmpty()) return ByteArray(0)
        val base = pts.first().epochSec
        val buf = ByteBuffer.allocate(HEADER + RECORD * pts.size)
        buf.putLong(base)
        for (p in pts) {
            buf.putInt(Math.toIntExact(p.epochSec - base))
            buf.put(p.bpm.coerceIn(0, 255).toByte())
        }
        return buf.array()
    }

    fun unpack(packed: ByteArray): List<HrPoint> {
        if (packed.size < HEADER) return emptyList()
        val buf = ByteBuffer.wrap(packed)
        val base = buf.getLong()
        return List((packed.size - HEADER) / RECORD) {
            val offset = buf.getInt()
            HrPoint(base + offset, buf.get().toInt() and 0xFF)
        }
    }
}
