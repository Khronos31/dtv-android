package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class Px4SatelliteInventoryTest {
    @Test
    fun liveNitInventoryHasExactTokensAndTsidAssignments() {
        assertEquals(38, PX4_SATELLITE_CHANNELS.size)
        assertEquals(38, PX4_SATELLITE_CHANNELS.map { it.token }.toSet().size)

        val expectedBs = mapOf(
            "BS01_0" to 16400, "BS01_1" to 16401, "BS01_2" to 16402,
            "BS03_0" to 16432, "BS03_1" to 17969, "BS03_2" to 17970,
            "BS05_0" to 17488, "BS05_1" to 17489,
            "BS09_0" to 16528, "BS09_1" to 16530,
            "BS13_0" to 16592, "BS13_1" to 16593, "BS13_2" to 18130,
            "BS15_0" to 16625, "BS15_1" to 16626, "BS15_2" to 18675,
            "BS19_0" to 18224, "BS19_1" to 18225, "BS19_2" to 18226,
            "BS19_3" to 18227,
            "BS21_0" to 18256, "BS21_1" to 18257, "BS21_2" to 18258,
            "BS23_0" to 18288, "BS23_1" to 18801, "BS23_2" to 18803
        )
        val actualBs = PX4_SATELLITE_CHANNELS
            .filter { it.type == Px4SatelliteChannelType.BS }
            .associate { it.token to it.tsid }
        assertEquals(expectedBs.size, actualBs.size)
        assertEquals(expectedBs.mapValues { (_, value) -> value }, actualBs)

        val expectedCs = setOf("CS2", "CS4", "CS6", "CS8", "CS10", "CS12",
            "CS14", "CS16", "CS18", "CS20", "CS22", "CS24")
        val actualCs = PX4_SATELLITE_CHANNELS
            .filter { it.type == Px4SatelliteChannelType.CS }
        assertEquals(expectedCs, actualCs.map { it.token }.toSet())
        actualCs.forEach { assertNull(it.tsid) }
    }

    @Test
    fun renderedConfigContainsOnlyExpectedSatelliteSelectors() {
        val rendered = renderPx4SatelliteChannelConfig()
        assertEquals(38, Regex("(?m)^  - name:").findAll(rendered).count())
        assertEquals(26, Regex("(?m)^    extra-args: '--tsid=").findAll(rendered).count())
        PX4_SATELLITE_CHANNELS
            .filter { it.type == Px4SatelliteChannelType.BS }
            .forEach { channel ->
                assertTrue(rendered.contains(
                    "channel: '${channel.token}'\n    extra-args: '--tsid=${channel.tsid}'"
                ))
            }
        PX4_SATELLITE_CHANNELS
            .filter { it.type == Px4SatelliteChannelType.CS }
            .forEach { channel ->
                assertFalse(rendered.contains("channel: '${channel.token}'\n    extra-args:"))
            }
    }
}
