package dev.baseline.fit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun fitFixture(name: String): ByteArray = checkNotNull(object {}.javaClass.getResourceAsStream("/fixtures/fit/$name.fit")) { "missing fit $name" }.readBytes()

fun zipOf(entryName: String, bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use {
        it.putNextEntry(ZipEntry(entryName))
        it.write(bytes)
        it.closeEntry()
    }
    return out.toByteArray()
}

class FitReaderTest {
    private fun read(name: String) = FitReader.read(fitFixture(name))

    private fun gaps(s: ActivityStreams) = s.epochSec.toList().zipWithNext { a, b -> b - a }

    @Test
    fun runHasOneSecondHrPowerGps() {
        val a = read("run")
        assertEquals(1655, a.streams.epochSec.size)
        assertTrue(gaps(a.streams).all { it == 1L })
        assertNotNull(a.streams.powerW)
        assertNotNull(a.streams.lat)
        assertEquals(6, a.laps.size)
    }

    @Test
    fun roadRideHasNoPower() {
        val a = read("road_ride")
        assertNull(a.streams.powerW)
        assertTrue(a.streams.hr.count { it >= 0 } >= 3800)
    }

    @Test
    fun virtualRideHasPower() {
        assertNotNull(read("virtual_ride").streams.powerW)
    }

    @Test
    fun swimHasLengths() {
        val a = read("swim")
        assertEquals(36, a.lengths.size)
        val first = a.lengths.first()
        assertEquals("freestyle", first.stroke)
        assertEquals(15, first.strokes)
        assertEquals(30.937, first.durationSec, 0.001)
        assertNotNull(a.poolLengthM)
    }

    @Test
    fun strengthHasSmartRecordedHr() {
        val a = read("strength")
        assertEquals(413, a.streams.epochSec.size)
        val g = gaps(a.streams)
        assertTrue(g.containsAll(listOf(1L, 2L, 4L))) // smart recording: also 3, 5..15 s in the real file
        assertEquals(18L, g.max())
        assertEquals(1, a.sets.size)
        assertEquals(7, a.sets.single().reps)
    }

    /** Every column as a list (null stays null) so equality is structural. */
    private fun ActivityStreams.cols() = listOf(
        epochSec.toList(),
        hr.toList(),
        distanceM?.toList(),
        speedMs?.toList(),
        powerW?.toList(),
        altitudeM?.toList(),
        lat?.toList(),
        lon?.toList(),
        cadence?.toList(),
    )

    @Test
    fun noNaNAnywhere() {
        for (n in listOf("run", "road_ride", "virtual_ride", "indoor_ride", "swim", "strength")) {
            val nan =
                read(n)
                    .streams
                    .cols()
                    .filterNotNull()
                    .flatten()
                    .any { (it is Float && it.isNaN()) || (it is Double && it.isNaN()) }
            assertFalse(n, nan)
        }
    }

    @Test
    fun packRoundTrips() {
        for (n in listOf("run", "road_ride", "strength")) {
            val s = read(n).streams
            assertEquals(n, s.cols(), ActivityStreams.unpack(s.pack()).cols())
        }
        val bare = ActivityStreams(longArrayOf(1, 2), intArrayOf(-1, 90), null, null, null, null, null, null, null)
        assertEquals(bare.cols(), ActivityStreams.unpack(bare.pack()).cols())
    }

    @Test
    fun readZipReturnsFitEntry() {
        val fit = fitFixture("run")
        assertArrayEquals(fit, FitReader.readZip(zipOf("123_ACTIVITY.fit", fit)))
    }

    @Test
    fun readZipWithoutFitEntryOrCorruptThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) { FitReader.readZip(zipOf("a.txt", byteArrayOf(1))) }
        assertThrows(IllegalArgumentException::class.java) { FitReader.readZip(byteArrayOf(1, 2, 3)) }
        val z = zipOf("a.fit", fitFixture("run"))
        assertThrows(IllegalArgumentException::class.java) { FitReader.readZip(z.copyOf(z.size / 2)) }
    }
}
