package dev.baseline.garmin

data class GarminRequest(val path: String, val query: Map<String, String>, val accept: String)

const val FIT_ACCEPT = "application/zip, application/x-zip-compressed, application/octet-stream"
private const val JSON_ACCEPT = "application/json"
private const val PAGE_SIZE = 50

/** Garmin Personal API endpoints (design spec §2). [maxDays] is the widest range one call accepts (0 = n/a). */
enum class GarminEndpoint(val maxDays: Int) {
    PROFILE(0),
    HR_ZONES(0),
    DAILY_SUMMARIES(180),
    HRV_RANGE(180),
    SLEEP_SUMMARIES(28),
    BODY_BATTERY(28),
    STRESS(7),
    STEPS(7),
    SLEEP_DAY(1),
    HEART_RATE_DAY(1),
    HRV_DAY(1),
    SPO2_DAY(1),
    ACTIVITY_PAGE(0),
    ACTIVITY_RANGE(7),
    ACTIVITY_DETAIL(0),
    FIT(0),
    ;

    /** [key] is "yyyy-MM-dd", "start..end", a page index or an activity id, depending on the endpoint. */
    fun request(key: String): GarminRequest {
        val (a, b) = key.split("..").let { it[0] to it.getOrElse(1) { "" } }

        fun get(path: String, query: Map<String, String> = emptyMap()) = GarminRequest(path, query, JSON_ACCEPT)

        fun range(path: String) = get(path, mapOf("startDate" to a, "endDate" to b))
        return when (this) {
            PROFILE -> {
                get("/userprofile-service/userprofile/settings")
            }

            HR_ZONES -> {
                get("/biometric-service/heartRateZones/v2")
            }

            DAILY_SUMMARIES -> {
                range("/usersummary-service/v2/dailysummaries")
            }

            HRV_RANGE -> {
                get("/hrv-service/hrv/daily/$a/$b")
            }

            SLEEP_SUMMARIES -> {
                range("/sleep-service/sleep/dailySleepSummariesByDate")
            }

            // chunks=288: a sample every 3 minutes (576 rows/day in pairs) instead of 6 points.
            BODY_BATTERY -> {
                get(
                    "/wellness-service/wellness/bodyBattery/reports/daily",
                    mapOf("startDate" to a, "endDate" to b, "chunks" to "288"),
                )
            }

            STRESS -> {
                get("/usersummary-service/stats/stress/daily/$a/$b")
            }

            STEPS -> {
                get("/usersummary-service/stats/steps/daily/$a/$b")
            }

            SLEEP_DAY -> {
                get("/sleep-service/sleep/dailySleep", mapOf("date" to key))
            }

            HEART_RATE_DAY -> {
                get("/wellness-service/wellness/dailyHeartRate", mapOf("date" to key))
            }

            HRV_DAY -> {
                get("/hrv-service/hrv/$key")
            }

            SPO2_DAY -> {
                get("/wellness-service/wellness/daily/spo2acclimation/$key")
            }

            ACTIVITY_PAGE -> {
                get(
                    ACTIVITY_SEARCH,
                    mapOf("start" to (key.toInt() * PAGE_SIZE).toString(), "limit" to PAGE_SIZE.toString()),
                )
            }

            ACTIVITY_RANGE -> {
                get(
                    ACTIVITY_SEARCH,
                    mapOf("startDate" to a, "endDate" to b, "limit" to PAGE_SIZE.toString(), "start" to "0"),
                )
            }

            ACTIVITY_DETAIL -> {
                get("/activity-service/activity/$key")
            }

            FIT -> {
                GarminRequest("/download-service/files/activity/$key", emptyMap(), FIT_ACCEPT)
            }
        }
    }

    private companion object {
        const val ACTIVITY_SEARCH = "/activitylist-service/activities/search/activities"
    }
}
