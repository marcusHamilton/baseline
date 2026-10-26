package dev.baseline.data

enum class SportFamily {
    RUN,
    RIDE,
    SWIM,
    STRENGTH,
    OTHER,
    ;

    companion object {
        private val byTypeKey: Map<String, SportFamily> =
            buildMap {
                listOf("running", "trail_running", "track_running", "treadmill_running").forEach { put(it, RUN) }
                listOf("road_biking", "virtual_ride", "indoor_cycling", "cycling", "mountain_biking", "gravel_cycling")
                    .forEach { put(it, RIDE) }
                listOf("lap_swimming", "open_water_swimming").forEach { put(it, SWIM) }
                put("strength_training", STRENGTH)
            }

        /** Unknown typeKeys fall into OTHER. */
        fun of(typeKey: String): SportFamily = byTypeKey[typeKey] ?: OTHER
    }
}

private val EXCLUDED_FROM_DISTANCE_RECORDS =
    setOf("treadmill_running", "virtual_ride", "indoor_cycling", "open_water_swimming")

fun SportFamily.countsForDistanceRecords(typeKey: String): Boolean = (this == SportFamily.RUN || this == SportFamily.RIDE || this == SportFamily.SWIM) &&
    typeKey !in EXCLUDED_FROM_DISTANCE_RECORDS
