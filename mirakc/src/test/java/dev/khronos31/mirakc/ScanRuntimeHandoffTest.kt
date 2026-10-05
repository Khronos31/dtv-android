package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRuntimeHandoffTest {
    @Test
    fun cancelBeforeNativeHandoffPreventsRuntimeStart() {
        val handoff = ScanRuntimeHandoff()
        assertTrue(handoff.begin(41L))
        assertEquals(ScanRuntimeHandoff.CancelDecision.CanceledPreparation, handoff.cancel(41L))
        var nativeStarts = 0
        assertFalse(handoff.handoffToNative(41L) { nativeStarts++; true })
        assertEquals(0, nativeStarts)
    }

    @Test
    fun cancelAfterHandoffTargetsExactlyTheNativeScanThatWonTheRace() {
        val handoff = ScanRuntimeHandoff()
        assertTrue(handoff.begin(42L))
        var nativeStarts = 0
        assertTrue(handoff.handoffToNative(42L) { nativeStarts++; true })
        assertEquals(ScanRuntimeHandoff.CancelDecision.CancelNative(42L), handoff.cancel(42L))
        assertEquals(1, nativeStarts)
        assertFalse(handoff.finish(41L)) // stale completion cannot retire a later request
        assertFalse(handoff.begin(43L)) // no new request until supervisor cancellation returns
        assertTrue(handoff.finish(42L))
        assertTrue(handoff.begin(43L))
        assertTrue(handoff.ownsPreparation(43L))
    }

    @Test
    fun cancelFailureRestoresSameNativeOperationWithoutAdmittingRetry() {
        val handoff = ScanRuntimeHandoff()
        assertTrue(handoff.begin(44L))
        assertTrue(handoff.handoffToNative(44L) { true })
        assertEquals(ScanRuntimeHandoff.CancelDecision.CancelNative(44L), handoff.cancel(44L))
        assertFalse(handoff.begin(45L))
        assertTrue(handoff.restoreNativeAfterCancelFailure(44L))
        assertTrue(handoff.canCancel(44L))
        assertTrue(handoff.cancel(44L) is ScanRuntimeHandoff.CancelDecision.CancelNative)
        assertTrue(handoff.finish(44L))
        assertTrue(handoff.begin(45L))
    }

    @Test
    fun preparedResultCommitCannotBeOvertakenByCancelOrStaleCallback() {
        val handoff = ScanRuntimeHandoff()
        assertTrue(handoff.begin(51L))
        assertTrue(handoff.claimPreparedCommit(51L))
        assertEquals(ScanRuntimeHandoff.CancelDecision.AlreadyFinishing, handoff.cancel(51L))
        assertFalse(handoff.finish(50L))
        assertTrue(handoff.finish(51L))
        assertEquals(ScanRuntimeHandoff.CancelDecision.NotCancelable, handoff.cancel(51L))
    }

    @Test
    fun terminalFailureOrCancellationRetiresNativeOwnershipAndAllowsRetry() {
        listOf(61L, 62L).forEach { scanId ->
            val handoff = ScanRuntimeHandoff()
            assertTrue(handoff.begin(scanId))
            assertTrue(handoff.handoffToNative(scanId) { true })
            // Supervisor delivers every terminal scan state after process and
            // tuner teardown, including FAILED and INTERRUPTED outcomes.
            assertTrue(handoff.finish(scanId))
            assertTrue(handoff.begin(scanId + 100L))
            assertTrue(handoff.finish(scanId + 100L))
        }
    }
}
