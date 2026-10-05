package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedChannelsDirectSaveTest {
    @Test
    fun advancedEditAtomicallyReplacesEffectiveAndRecoveryValuesWithoutPendingApply() {
        val settings = MemorySettings()
        val store = TerrestrialChannelSettingsStore(settings)
        store.completeScan(7L, listOf(20, 21), allowEmptySetup = false)

        store.savePreparedChannels("24,22-23")

        assertEquals("v1|22,23,24", settings.get(TerrestrialChannelSettingsStore.ACTIVE_KEY))
        assertEquals("v1|22,23,24", settings.get(TerrestrialChannelSettingsStore.LAST_KNOWN_GOOD_KEY))
        assertNull(settings.get(TerrestrialChannelSettingsStore.PENDING_KEY))
        assertNull(settings.get(TerrestrialChannelSettingsStore.APPLYING_KEY))
        assertTrue(store.isSetupPrepared())
    }

    @Test
    fun malformedAdvancedEditLeavesStoredSettingsUntouched() {
        val settings = MemorySettings()
        val store = TerrestrialChannelSettingsStore(settings)
        store.completeScan(8L, listOf(20), allowEmptySetup = false)
        val before = settings.snapshot()

        val failure = runCatching { store.savePreparedChannels("62-13") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)

        assertEquals(before, settings.snapshot())
    }

    private class MemorySettings : StringSettings {
        private val values = linkedMapOf<String, String>()
        fun snapshot() = values.toMap()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun transaction(changes: Map<String, String?>) {
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
