package dev.baseline.data.parse

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrZonesParserTest {
    private fun parse(s: String) = HrZonesParser.parse(Json.parseToJsonElement(s))
    private fun entry(sport: String, max: Int, rest: Int) =
        """{"sport":"$sport","maxHeartRateUsed":$max,"restingHeartRateUsed":$rest,"zone1Floor":103}"""

    @Test fun fixtureGivesDefaultSportZones() {
        assertEquals(HrZones(205, 44, 103), HrZonesParser.parse(fixture("hr_zones.json")))
    }

    @Test fun picksTheDefaultEntryNotTheFirst() {
        assertEquals(HrZones(190, 50, 103), parse("[${entry("RUNNING", 200, 40)},${entry("DEFAULT", 190, 50)}]"))
    }

    @Test fun missingDefaultOrInvalidRangeIsNull() {
        assertNull(parse("[${entry("RUNNING", 200, 40)}]"))
        assertNull(parse("[]"))
        assertNull(parse("{}"))
        assertNull(parse("[${entry("DEFAULT", 44, 44)}]"))
        assertNull(parse("[${entry("DEFAULT", 30, 44)}]"))
        assertNull(parse("[${entry("DEFAULT", 205, 0)}]"))
        assertNull(parse("""[{"sport":"DEFAULT","restingHeartRateUsed":44,"zone1Floor":103}]"""))
        assertNull(parse("""[{"sport":"DEFAULT","maxHeartRateUsed":205,"restingHeartRateUsed":44}]"""))
    }
}
