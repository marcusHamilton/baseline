package dev.baseline.data.parse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class HrZones(val maxHr: Int, val restingHr: Int, val zone1Floor: Int)

object HrZonesParser {
    /** The `DEFAULT` sport entry of the HR-zones list; null when it is missing, lacks a field, or `maxHr > restingHr > 0` fails (Trimp would return 0 silently). */
    fun parse(json: JsonElement): HrZones? {
        val e = (json as? JsonArray)?.firstOrNull { it is JsonObject && it.str("sport") == "DEFAULT" } as? JsonObject ?: return null
        val max = e.int("maxHeartRateUsed") ?: return null
        val rest = e.int("restingHeartRateUsed") ?: return null
        val floor = e.int("zone1Floor") ?: return null
        return if (max > rest && rest > 0) HrZones(max, rest, floor) else null
    }
}
