package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedScanCommitTest {
    @Test
    fun nonemptyScanAtomicallyPreparesEffectiveChannels() {
        val memory = MemorySettings()
        val settings = TerrestrialChannelSettingsStore(memory)

        assertTrue(settings.completeScan(41, listOf(13, 16), allowEmptySetup = false))
        assertTrue(settings.isSetupPrepared())
        assertEquals(listOf(13, 16), settings.active().channels.map { it.number })
        assertNull(settings.snapshot().pending)
        assertEquals("41", memory.values[TerrestrialChannelSettingsStore.LAST_APPLIED_SCAN_KEY])
    }

    @Test
    fun firstEmptyScanCanPrepareSatelliteOnlyWithoutMarkingKantoAsGood() {
        val memory = MemorySettings()
        val settings = TerrestrialChannelSettingsStore(memory)

        assertTrue(settings.completeScan(42, emptyList(), allowEmptySetup = true))
        assertTrue(settings.isSetupPrepared())
        assertTrue(settings.active().channels.isEmpty())
        memory.values[TerrestrialChannelSettingsStore.ACTIVE_KEY] = "not-a-valid-channel-snapshot"
        val recovered = settings.active()
        assertTrue(recovered.channels.isEmpty())
        assertEquals(TerrestrialSettingsSource.MALFORMED_FALLBACK, recovered.source)
        assertEquals(
            TerrestrialChannelSettings.SCHEMA_PREFIX,
            memory.values[TerrestrialChannelSettingsStore.LAST_KNOWN_GOOD_KEY]
        )
    }

    @Test
    fun emptyRescanKeepsPreviouslyPreparedChannels() {
        val memory = MemorySettings()
        val settings = TerrestrialChannelSettingsStore(memory)
        settings.completeScan(43, listOf(21, 22), allowEmptySetup = false)

        assertTrue(settings.completeScan(44, emptyList(), allowEmptySetup = true))
        assertEquals(listOf(21, 22), settings.active().channels.map { it.number })
        assertTrue(settings.isSetupPrepared())
        assertEquals("44", memory.values[TerrestrialChannelSettingsStore.LAST_APPLIED_SCAN_KEY])
    }

    private class MemorySettings : StringSettings {
        val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun transaction(changes: Map<String, String?>) {
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }

}
