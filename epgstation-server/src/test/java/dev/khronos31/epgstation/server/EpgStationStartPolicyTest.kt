package dev.khronos31.epgstation.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgStationStartPolicyTest {
    @Test
    fun ordinaryAndRepeatedStartsDoNotRestartNode() {
        var restarts = 0

        repeat(3) {
            assertFalse(EpgStationStartPolicy.handle(null) { restarts++ })
        }
        assertFalse(EpgStationStartPolicy.handle("unrelated-action") { restarts++ })

        assertEquals(0, restarts)
    }

    @Test
    fun explicitSettingsRestartActionRestartsNodeOnce() {
        var restarts = 0

        assertTrue(EpgStationStartPolicy.handle(EpgStationStartPolicy.ACTION_RESTART) { restarts++ })

        assertEquals(1, restarts)
    }
}
