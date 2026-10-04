package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrScanStatusTest {
    @Test
    fun queuedScanIsCancellableButCannotApplyResults() {
        val status = GrScanStatus.parse("queued\n0\n50\n\n\n0\n123\nNONE\n")!!

        assertTrue(status.isRunning)
        assertTrue(status.isIndeterminate)
        assertFalse(status.isApplicable)
        assertEquals("スキャン待機中", status.summary())
    }

    @Test
    fun runningScanIsIndeterminateUntilFirstChannelProgressArrives() {
        val starting = GrScanStatus.parse("running\n0\n50\n\n\n0\n124\nNONE\n")!!
        val progressing = GrScanStatus.parse("running\n1\n50\n13\n13\n0\n124\nNONE\n")!!

        assertTrue(starting.isIndeterminate)
        assertFalse(progressing.isIndeterminate)
    }

    @Test
    fun parsesProgressAndOnlyCompleteNonEmptyResultsAreApplicable() {
        val running = GrScanStatus.parse("running\n17\n50\n29\n13,16,29\n0\n12345\nNONE\n")!!
        assertTrue(running.isRunning)
        assertEquals(17, running.completed)
        assertEquals(29, running.currentChannel)
        assertFalse(running.isApplicable)

        val completed = GrScanStatus.parse("complete\n50\n50\n62\n13,16,29\n0\n12345\nNONE\n")!!
        assertTrue(completed.isApplicable)
        assertNull(completed.errorCode)

        val failed = GrScanStatus.parse("failed\n50\n50\n62\n13,16,29\n1\n12345\nCHANNEL_SCAN_FAILED\n")!!
        assertFalse(failed.isApplicable)
    }

    @Test
    fun malformedOrEmptyResultsAreNotApplicable() {
        assertNull(GrScanStatus.parse("complete\n50\n50\n62\n\n0\n12345\nNONE\n"))
        assertNull(GrScanStatus.parse("complete\n51\n50\n62\n13\n0\n12345\nNONE\n"))
        assertNull(GrScanStatus.parse("complete\n50\n50\n62\n13\n0\n0\nNONE\n"))
    }

    @Test
    fun completeScanStagesChannelsOnceAndEmptyResultsKeepExistingInput() {
        val values = MemorySettings().apply {
            put(TerrestrialChannelSettingsStore.PENDING_KEY, "v1|21,22")
        }
        val store = TerrestrialChannelSettingsStore(values)
        assertFalse(runCatching { store.applyScanResult(7, emptyList()) }.isSuccess)
        assertEquals("v1|21,22", values.get(TerrestrialChannelSettingsStore.PENDING_KEY))

        assertTrue(store.applyScanResult(7, listOf(29, 13, 29)))
        assertEquals("v1|13,29", values.get(TerrestrialChannelSettingsStore.PENDING_KEY))
        store.savePending("16")
        assertFalse(store.applyScanResult(7, listOf(13, 29)))
        assertEquals("v1|16", values.get(TerrestrialChannelSettingsStore.PENDING_KEY))
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
