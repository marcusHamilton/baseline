package dev.baseline.data.parse

import dev.baseline.data.SportFamily
import dev.baseline.data.db.ActivityEntity
import dev.baseline.garmin.GarminTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.DateTimeException

object ActivityParser {
    /**
     * Activity-list item -> [ActivityEntity]. Only activityId, activityType.typeKey, startTimeGMT and
     * startTimeLocal are required; anything else missing becomes null. Throws [IllegalArgumentException]
     * when a required field is missing or malformed (the sync engine marks that task FAILED).
     */
    fun parseListItem(o: JsonObject): ActivityEntity {
        val id = o.lng("activityId") ?: throw IllegalArgumentException("missing activityId")
        val typeKey = o.obj("activityType")?.str("typeKey") ?: throw IllegalArgumentException("activity $id: missing typeKey")
        val gmt = o.str("startTimeGMT") ?: throw IllegalArgumentException("activity $id: missing startTimeGMT")
        val local = o.str("startTimeLocal") ?: throw IllegalArgumentException("activity $id: missing startTimeLocal")
        return try {
            ActivityEntity(
                id = id,
                name = o.str("activityName"),
                typeKey = typeKey,
                sportFamily = SportFamily.of(typeKey),
                startTimeLocal = GarminTime.parseLocal(local),
                day = GarminTime.activityDay(local),
                startGmt = GarminTime.parseGmt(gmt),
                durationSec = o.dbl("duration") ?: throw IllegalArgumentException("activity $id: missing duration"),
                distanceM = o.dbl("distance"),
                avgHr = o.dbl("averageHR"),
                maxHr = o.dbl("maxHR"),
                zone1Sec = o.dbl("hrTimeInZone_1"),
                zone2Sec = o.dbl("hrTimeInZone_2"),
                zone3Sec = o.dbl("hrTimeInZone_3"),
                zone4Sec = o.dbl("hrTimeInZone_4"),
                zone5Sec = o.dbl("hrTimeInZone_5"),
                garminLoad = o.dbl("activityTrainingLoad"),
                elevationGainM = o.dbl("elevationGain"),
                avgPower = o.dbl("avgPower"),
                normPower = o.dbl("normPower"),
                fastestSplitsJson = fastestSplits(o),
                fitPath = null,
                hrCoverage = null,
                // Garmin stores poolLength in centimetres (2500.0 = a 25 m pool).
                poolLengthM = o.dbl("poolLength")?.let { it / 100 },
            )
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("activity $id: bad start time", e)
        }
    }

    private fun fastestSplits(o: JsonObject): String? =
        o.filter { (k, v) -> k.startsWith("fastestSplit_") && v !is JsonNull }.takeIf { it.isNotEmpty() }?.let { JsonObject(it).toString() }
}
