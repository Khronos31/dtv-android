package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeResourceCycleTest {
    private data class AdapterGeneration(val id: Int)

    @Test
    fun scanCompletionAndRepeatedPublicStartsGetFreshAdaptersOnlyAfterOwnerQuiescence() {
        var nextId = 1
        val initial = AdapterGeneration(0)
        val cycle = RuntimeResourceCycle(initial) { AdapterGeneration(nextId++) }
        val gate = RuntimeOwnerGate()
        assertSame(initial, cycle.current())
        assertSame(initial, cycle.replaceAfterQuiescence(quiescent = false))

        // The scan runtime reaches terminal cleanup while its owner worker is
        // still registered. A public start cannot adopt new resources yet.
        assertTrue(gate.ownerStarted())
        gate.beginStop()
        cycle.markTerminallyClosed(initial)
        assertThrows(IllegalStateException::class.java) {
            cycle.replaceAfterQuiescence(quiescent = gate.isQuiescent() && gate.mayStart())
        }
        gate.ownerFinished()
        gate.finishStop()

        val publicRuntime = cycle.replaceAfterQuiescence(quiescent = gate.isQuiescent() && gate.mayStart())
        assertNotSame(initial, publicRuntime)
        assertEquals(1, publicRuntime.id)
        cycle.markTerminallyClosed(initial) // A late old-generation callback cannot retire the new set.
        assertSame(publicRuntime, cycle.replaceAfterQuiescence(quiescent = false))

        // Stopping and explicitly starting the public runtime follows the
        // same terminal-close barrier and creates another independent adapter set.
        assertTrue(gate.ownerStarted())
        gate.beginStop()
        cycle.markTerminallyClosed(publicRuntime)
        gate.ownerFinished()
        gate.finishStop()

        val restartedPublic = cycle.replaceAfterQuiescence(quiescent = gate.isQuiescent() && gate.mayStart())
        assertNotSame(publicRuntime, restartedPublic)
        assertEquals(2, restartedPublic.id)
    }
}
