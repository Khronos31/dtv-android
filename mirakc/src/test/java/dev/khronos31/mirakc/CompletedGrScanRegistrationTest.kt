package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedGrScanRegistrationTest {
    @Test
    fun onlyCompleteNonEmptyResultStagesChannelsWithoutChangingActiveConfig() {
        val values = MemorySettings().apply {
            put(
                TerrestrialChannelSettingsStore.ACTIVE_KEY,
                TerrestrialChannelSettings.serialize(listOf(TerrestrialChannel(21), TerrestrialChannel(22)))
            )
        }
        val store = TerrestrialChannelSettingsStore(values)

        val empty = GrScanStatus.parse("empty\n50\n50\n62\n\n0\n11\nNO_CHANNELS_FOUND\n")!!
        val failed = GrScanStatus.parse("failed\n50\n50\n62\n13,20\n1\n12\nCHANNEL_SCAN_FAILED\n")!!
        val canceled = GrScanStatus.parse("interrupted\n4\n50\n17\n13\n0\n13\nCANCELED\n")!!
        assertFalse(registerCompletedGrScan(store, empty))
        assertFalse(registerCompletedGrScan(store, failed))
        assertFalse(registerCompletedGrScan(store, canceled))
        assertEquals("v1|21,22", values.get(TerrestrialChannelSettingsStore.ACTIVE_KEY))
        assertEquals(null, values.get(TerrestrialChannelSettingsStore.PENDING_KEY))

        val complete = GrScanStatus.parse("complete\n50\n50\n62\n13,20,13\n0\n14\nNONE\n")!!
        assertTrue(registerCompletedGrScan(store, complete))
        assertFalse(registerCompletedGrScan(store, complete))
        assertEquals("v1|21,22", values.get(TerrestrialChannelSettingsStore.ACTIVE_KEY))
        assertEquals("v1|13,20", values.get(TerrestrialChannelSettingsStore.PENDING_KEY))
        assertEquals("14", values.get(TerrestrialChannelSettingsStore.LAST_APPLIED_SCAN_KEY))
    }

    private class MemorySettings : StringSettings {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun transaction(changes: Map<String, String?>) {
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
