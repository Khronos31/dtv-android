package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrestrialChannelSettingsTest {
    @Test
    fun missingSettingKeepsTheOriginalKantoDefinition() {
        val result = TerrestrialChannelSettings.parseStored(null)

        assertEquals(TerrestrialSettingsSource.UNSET, result.source)
        assertEquals(
            listOf(16, 21, 22, 23, 24, 25, 26, 27, 30, 31, 32),
            result.channels.map { it.number }
        )
        assertEquals("TOKYO MX", result.channels.first().name)
    }

    @Test
    fun inputRangesAreUniqueSortedAndHaveStableNames() {
        val channels = TerrestrialChannelSettings.parseInput("13,16,21-27,16")

        assertEquals((listOf(13, 16) + (21..27)).map { it }, channels.map { it.number })
        assertEquals(listOf("GR-13", "GR-16", "GR-21", "GR-27"), listOf(channels[0].name, channels[1].name, channels[2].name, channels.last().name))
        assertEquals("v1|13,16,21,22,23,24,25,26,27", TerrestrialChannelSettings.serialize(channels))
    }

    @Test
    fun explicitEmptyDisablesTerrestrialWithoutBeingUnset() {
        val result = TerrestrialChannelSettings.parseStored("v1|")

        assertEquals(TerrestrialSettingsSource.EXPLICIT_EMPTY, result.source)
        assertTrue(result.channels.isEmpty())
        assertEquals("channels: []\n", renderTerrestrialChannelConfig(result.channels))
        assertEquals("channels:\n", renderTerrestrialChannelConfig(result.channels, appendListHeaderWhenEmpty = true))
    }

    @Test
    fun generatedConfigurationUsesStableCustomNamesAndPreservesDefaultNames() {
        val defaultConfig = renderTerrestrialChannelConfig(TerrestrialChannelSettings.DEFAULT_CHANNELS)
        val customConfig = renderTerrestrialChannelConfig(TerrestrialChannelSettings.parseInput("13,16,21-22"))

        assertTrue(defaultConfig.contains("name: TOKYO MX\n    type: GR\n    channel: '16'"))
        assertTrue(customConfig.contains("name: GR-13\n    type: GR\n    channel: '13'"))
        assertTrue(!customConfig.contains("TOKYO MX"))
    }

    @Test
    fun malformedAndFutureVersionsFallbackWithoutOverwritingStoredData() {
        val fallback = listOf(TerrestrialChannel(44, "GR-44"))
        val malformed = TerrestrialChannelSettings.parseStored("v1|12", fallback)
        val future = TerrestrialChannelSettings.parseStored("v2|44", fallback)

        assertEquals(TerrestrialSettingsSource.MALFORMED_FALLBACK, malformed.source)
        assertEquals(TerrestrialSettingsSource.FUTURE_FALLBACK, future.source)
        assertEquals(fallback, malformed.channels)
        assertEquals(fallback, future.channels)
        assertTrue(malformed.error!!.contains("last-known-good/default"))
    }

    @Test
    fun invalidInputsAreRejectedBeforePendingSave() {
        listOf("12", "63", "21-20", "13,,14", "13-x", "+13").forEach { input ->
            try {
                TerrestrialChannelSettings.parseInput(input)
                throw AssertionError("accepted invalid input $input")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun activateAndRollbackAreAStateTransaction() {
        val values = MemorySettings()
        val store = TerrestrialChannelSettingsStore(values)
        store.savePending("13,16")
        store.activatePending()

        assertEquals(listOf(13, 16), store.active().channels.map { it.number })
        assertEquals(null, values.get(TerrestrialChannelSettingsStore.PENDING_KEY))
        assertEquals("v1|default", values.get(TerrestrialChannelSettingsStore.APPLYING_KEY))
        store.restoreLastKnownGood()
        assertEquals(listOf(16, 21, 22, 23, 24, 25, 26, 27, 30, 31, 32), store.active().channels.map { it.number })
        assertEquals("TOKYO MX", store.active().channels.first().name)
        assertEquals(null, values.get(TerrestrialChannelSettingsStore.APPLYING_KEY))
    }

    @Test
    fun interruptedApplyRecoversPreviousActiveConfiguration() {
        val values = MemorySettings()
        val store = TerrestrialChannelSettingsStore(values)
        store.savePending("13")
        store.activatePending()

        TerrestrialChannelSettingsStore(values).recoverIncompleteApply()

        assertEquals(listOf(16, 21, 22, 23, 24, 25, 26, 27, 30, 31, 32), store.active().channels.map { it.number })
        assertEquals(null, values.get(TerrestrialChannelSettingsStore.APPLYING_KEY))
    }

    @Test
    fun malformedActiveUsesValidLastKnownGoodAndKeepsMalformedValueForUiError() {
        val values = MemorySettings().apply {
            put(TerrestrialChannelSettingsStore.ACTIVE_KEY, "v1|99")
            put(TerrestrialChannelSettingsStore.LAST_KNOWN_GOOD_KEY, "v1|13,14")
        }

        val result = TerrestrialChannelSettingsStore(values).active()

        assertEquals(TerrestrialSettingsSource.MALFORMED_FALLBACK, result.source)
        assertEquals(listOf(13, 14), result.channels.map { it.number })
        assertEquals("v1|99", values.get(TerrestrialChannelSettingsStore.ACTIVE_KEY))
    }

    @Test
    fun editorRestoresValidPendingInsteadOfOverwritingItWithActive() {
        val values = MemorySettings().apply {
            put(TerrestrialChannelSettingsStore.ACTIVE_KEY, "v1|21,22")
            put(TerrestrialChannelSettingsStore.PENDING_KEY, "v1|13,16")
        }

        val input = TerrestrialChannelSettingsStore(values).inputState()

        assertEquals(listOf(13, 16), input.channels.map { it.number })
        assertEquals(null, input.warning)
    }

    @Test
    fun editorUsesActiveAndKeepsWarningForMalformedOrFuturePending() {
        listOf("v1|12", "v2|13").forEach { pending ->
            val values = MemorySettings().apply {
                put(TerrestrialChannelSettingsStore.ACTIVE_KEY, "v1|21,22")
                put(TerrestrialChannelSettingsStore.PENDING_KEY, pending)
            }

            val input = TerrestrialChannelSettingsStore(values).inputState()

            assertEquals(listOf(21, 22), input.channels.map { it.number })
            assertTrue(input.warning?.contains("Invalid terrestrial channel settings") == true)
        }
    }

    private class MemorySettings : StringSettings {
        private val values = linkedMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun transaction(changes: Map<String, String?>) {
            val copy = values.toMutableMap()
            changes.forEach { (key, value) -> if (value == null) copy.remove(key) else copy[key] = value }
            values.clear()
            values.putAll(copy)
        }
    }
}
