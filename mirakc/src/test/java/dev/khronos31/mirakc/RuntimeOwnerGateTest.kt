package dev.khronos31.mirakc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeOwnerGateTest {
    @Test
    fun lateStartupCannotAdmitAnotherOwnerUntilCancellationBarrierFinishes() {
        val gate = RuntimeOwnerGate()

        assertTrue(gate.ownerStarted()) // scan startup worker still owns resources
        gate.beginStop() // user cancels while startup/probe is in flight
        assertFalse(gate.mayStart())
        assertFalse(gate.ownerStarted()) // a retry/public start cannot race cleanup
        assertFalse(gate.isQuiescent())

        gate.ownerFinished()
        assertTrue(gate.isQuiescent())
        gate.finishStop() // broker/PX4 cleanup completes after all workers retire

        assertTrue(gate.mayStart())
        assertTrue(gate.ownerStarted()) // a retry is admitted only after teardown
        gate.ownerFinished()
    }

    @Test
    fun duplicateWorkerRegistrationIsCountedUntilTheLastOwnerRetires() {
        val gate = RuntimeOwnerGate()
        assertTrue(gate.ownerStarted())
        assertTrue(gate.ownerStarted())
        gate.beginStop()

        gate.ownerFinished()
        assertFalse(gate.isQuiescent())
        assertFalse(gate.mayStart())
        gate.ownerFinished()
        assertTrue(gate.isQuiescent())
        gate.finishStop()
        assertTrue(gate.mayStart())
    }
}
