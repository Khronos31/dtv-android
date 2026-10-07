package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrScanStatusTest {
    @Test
    fun queuedScanIsCancellableButCannotApplyResults() {
        val status = GrScanStatus.parse("queued\n0\n40\n\n\n0\n123\nNONE\n")!!

        assertTrue(status.isRunning)
        assertTrue(status.isIndeterminate)
        assertFalse(status.isApplicable)
        assertEquals("スキャン待機中", status.summary())
    }

    @Test
    fun runningScanIsIndeterminateUntilFirstChannelProgressArrives() {
        val starting = GrScanStatus.parse("running\n0\n40\n\n\n0\n124\nNONE\n")!!
        val progressing = GrScanStatus.parse("running\n1\n40\n13\n13\n0\n124\nNONE\n")!!

        assertTrue(starting.isIndeterminate)
        assertFalse(progressing.isIndeterminate)
    }

    @Test
    fun parsesProgressAndOnlyCompleteNonEmptyResultsAreApplicable() {
        val running = GrScanStatus.parse("running\n17\n40\n29\n13,16,29\n0\n12345\nNONE\n")!!
        assertTrue(running.isRunning)
        assertEquals(17, running.completed)
        assertEquals(29, running.currentChannel)
        assertFalse(running.isApplicable)

        val completed = GrScanStatus.parse("complete\n40\n40\n52\n13,16,29\n0\n12345\nNONE\n")!!
        assertTrue(completed.isApplicable)
        assertNull(completed.errorCode)

        val failed = GrScanStatus.parse("failed\n40\n40\n52\n13,16,29\n1\n12345\nCHANNEL_SCAN_FAILED\n")!!
        assertFalse(failed.isApplicable)
    }

    @Test
    fun cancelPreservesSameScanNativeProgressAndClearsInFlightChannel() {
        val beforeStop = GrScanStatus.parse("running\n5\n40\n19\n16,17\n0\n1791140690515\nNONE\n")!!
        val stopped = GrScanStatus.parse("interrupted\n5\n40\n\n16,17\n0\n1791140690515\nINTERRUPTED\n")!!

        val canceled = GrScanStatus.canceledFromNative(
            scanId = beforeStop.scanId,
            stoppedStatus = stopped,
            statusBeforeStop = beforeStop
        )

        assertEquals(GrScanStatus.State.INTERRUPTED, canceled.state)
        assertEquals(5, canceled.completed)
        assertNull(canceled.currentChannel)
        assertEquals(listOf(16, 17), canceled.foundChannels)
        assertEquals("CANCELED", canceled.errorCode)
    }

    @Test
    fun cancelFallsBackWhenNeitherStatusBelongsToThisScan() {
        val stale = GrScanStatus.parse("interrupted\n5\n40\n\n16,17\n0\n100\nINTERRUPTED\n")!!
        val canceled = GrScanStatus.canceledFromNative(101, stale, null)

        assertEquals(GrScanStatus.interrupted(101), canceled)
    }

    @Test
    fun malformedOrEmptyResultsAreNotApplicable() {
        assertNull(GrScanStatus.parse("complete\n40\n40\n52\n\n0\n12345\nNONE\n"))
        assertNull(GrScanStatus.parse("complete\n41\n40\n52\n13\n0\n12345\nNONE\n"))
        assertNull(GrScanStatus.parse("complete\n40\n40\n52\n13\n0\n0\nNONE\n"))
    }

    @Test
    fun legacyFiftyChannelStateRemainsReadableForSafeRecovery() {
        val legacy = GrScanStatus.parse("running\n9\n50\n61\n13,61\n0\n12345\nNONE\n")!!

        assertEquals(50, legacy.total)
        assertEquals(9, legacy.completed)
        assertEquals(61, legacy.currentChannel)
        assertEquals(listOf(13, 61), legacy.foundChannels)
        assertEquals(40, GrScanStatus.queued(12346).total)
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
