package dev.khronos31.mirakc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAdmissionRetryGateTest {
    @Test
    fun explicitHostRetryAfterFailedAdmissionCanAcquireSameServiceAgain() {
        val gate = RuntimeAdmissionRetryGate()

        assertTrue(gate.beginAttempt())
        assertFalse(gate.beginAttempt()) // duplicate host requests don't overlap
        gate.markFailed() // failed initialization has completed its admission attempt

        assertTrue(gate.beginAttempt()) // later ACTION_HOST can retry this Service
        gate.markReady()
        assertFalse(gate.beginAttempt()) // an admitted owner is not replaced
    }

    @Test
    fun closingServiceRejectsPendingOrLaterRetry() {
        val gate = RuntimeAdmissionRetryGate()
        assertTrue(gate.beginAttempt())
        gate.markFailed()
        gate.close()

        assertFalse(gate.beginAttempt())
    }
}
