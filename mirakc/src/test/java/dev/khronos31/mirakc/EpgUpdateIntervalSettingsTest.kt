package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgUpdateIntervalSettingsTest {
    @Test
    fun acceptsArbitraryMinuteIntervalsAndTheFullDailyRange() {
        listOf(1, 7, 10, 59, 60, 1439, 1440).forEach { minutes ->
            assertEquals(minutes, EpgUpdateIntervalSettings.parseInput(" $minutes "))
        }
    }

    @Test
    fun rejectsValuesOutsideTheMinuteRange() {
        listOf("", "0", "1441", "1.5", "ten").forEach { input ->
            try {
                EpgUpdateIntervalSettings.parseInput(input)
                throw AssertionError("accepted invalid interval $input")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun savedIntervalSurvivesStoreRecreationAndInvalidSavedValueFallsBack() {
        val values = MemorySettings()
        assertEquals(10, EpgUpdateIntervalStore(values).minutes())
        assertEquals(7, EpgUpdateIntervalStore(values).save("7"))
        assertEquals(7, EpgUpdateIntervalStore(values).minutes())

        values.put(EpgUpdateIntervalSettings.KEY, "1441")
        assertEquals(10, EpgUpdateIntervalStore(values).minutes())
    }

    private class MemorySettings : StringSettings {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
}
