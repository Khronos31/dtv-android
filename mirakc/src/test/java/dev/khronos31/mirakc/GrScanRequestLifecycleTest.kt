package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrScanRequestLifecycleTest {
    @Test
    fun serviceOperationIdSurvivesStartupQueueUntilNativeTriggerActivation() {
        val lifecycle = GrScanRequestLifecycle()
        val serviceOperationId = 9_876_543_210L
        assertEquals(
            GrScanRequestLifecycle.Decision.WaitForStartup,
            lifecycle.request(
                processReady = false,
                startupActive = true,
                currentStatus = null,
                requestedScanId = serviceOperationId
            )
        )
        assertEquals(serviceOperationId, lifecycle.visibleStatus(null)?.scanId)

        var activatedId: Long? = null
        assertTrue(lifecycle.activateWhenReady(deferForReconfigure = false) { status ->
            activatedId = status.scanId
            true
        })
        assertEquals(serviceOperationId, activatedId)
        assertNull(lifecycle.visibleStatus(null))
    }

    @Test
    fun requestArrivingAfterStartupActivationWaitsForStableMonitorDrain() {
        val lifecycle = GrScanRequestLifecycle()

        assertTrue(lifecycle.activateWhenReady(deferForReconfigure = false) { error("no request yet") })
        assertEquals(
            GrScanRequestLifecycle.Decision.WaitForStartup,
            lifecycle.request(processReady = false, startupActive = true, currentStatus = null, nowMillis = 650)
        )
        assertEquals(GrScanStatus.State.QUEUED, lifecycle.visibleStatus(null)?.state)

        var scanMarkerCreated = false
        assertTrue(lifecycle.activateWhenReady(deferForReconfigure = false) {
            scanMarkerCreated = it.scanId == 650L
            scanMarkerCreated
        })
        assertTrue(scanMarkerCreated)
        val queuedOnDisk = GrScanStatus.parse("queued\n0\n40\n\n\n0\n650\nNONE\n")!!
        assertEquals(GrScanStatus.State.QUEUED, lifecycle.visibleStatus(queuedOnDisk)?.state)
    }

    @Test
    fun stoppedServiceRejectsButStartupRequestRemainsVisibleAndUpdatesFromPolledState() {
        val lifecycle = GrScanRequestLifecycle()
        val oldResult = GrScanStatus.parse("complete\n50\n50\n62\n13,16\n0\n120\nNONE\n")!!

        assertEquals(
            GrScanRequestLifecycle.Decision.Unavailable,
            lifecycle.request(processReady = false, startupActive = false, currentStatus = oldResult, nowMillis = 700)
        )

        val decision = lifecycle.request(
            processReady = false,
            startupActive = true,
            currentStatus = oldResult,
            nowMillis = 701
        )
        assertEquals(GrScanRequestLifecycle.Decision.WaitForStartup, decision)
        assertEquals(701L, lifecycle.visibleStatus(oldResult)?.scanId)
        assertEquals(GrScanStatus.State.QUEUED, lifecycle.visibleStatus(oldResult)?.state)
        assertEquals(
            GrScanRequestLifecycle.Decision.Busy,
            lifecycle.request(
                processReady = false,
                startupActive = true,
                currentStatus = lifecycle.visibleStatus(oldResult),
                nowMillis = 702
            )
        )

        val queued = lifecycle.visibleStatus(oldResult)!!
        var exposedToMirakc = false
        assertFalse(lifecycle.activateWhenReady(deferForReconfigure = true) {
            exposedToMirakc = true
            true
        })
        assertFalse(exposedToMirakc)
        assertEquals(GrScanStatus.State.QUEUED, lifecycle.visibleStatus(oldResult)?.state)
        assertTrue(lifecycle.activateWhenReady(deferForReconfigure = false) {
            exposedToMirakc = true
            it.scanId == queued.scanId
        })
        assertTrue(exposedToMirakc)
        assertEquals(GrScanStatus.State.QUEUED, lifecycle.visibleStatus(GrScanStatus.parse(queued.toFileContents()))?.state)
        val progress = GrScanStatus.parse("running\n8\n40\n20\n13,20\n0\n701\nNONE\n")!!
        assertEquals(8, lifecycle.visibleStatus(progress)?.completed)
        val complete = GrScanStatus.parse("complete\n40\n40\n52\n13,20\n0\n701\nNONE\n")!!
        assertTrue(lifecycle.visibleStatus(complete)?.isApplicable == true)
    }

    @Test
    fun busyRunningStoppedCancellationAndStartupFailureHaveDistinctOutcomes() {
        val lifecycle = GrScanRequestLifecycle()
        val running = GrScanStatus.parse("running\n3\n40\n15\n13\n0\n900\nNONE\n")!!

        assertEquals(
            GrScanRequestLifecycle.Decision.Busy,
            lifecycle.request(processReady = true, startupActive = false, currentStatus = running, nowMillis = 901)
        )

        assertEquals(
            GrScanRequestLifecycle.Decision.WaitForStartup,
            lifecycle.request(processReady = false, startupActive = true, currentStatus = null, nowMillis = 902)
        )
        assertTrue(lifecycle.cancelPending())
        val canceled = lifecycle.visibleStatus(running)!!
        assertEquals(GrScanStatus.State.INTERRUPTED, canceled.state)
        assertFalse(canceled.isApplicable)
        assertTrue(canceled.summary().contains("中断"))

        assertEquals(
            GrScanRequestLifecycle.Decision.WaitForStartup,
            lifecycle.request(processReady = false, startupActive = true, currentStatus = null, nowMillis = 903)
        )
        lifecycle.startupFailed()
        val failed = lifecycle.visibleStatus(null)!!
        assertEquals(GrScanStatus.State.FAILED, failed.state)
        assertEquals("STARTUP_FAILED", failed.errorCode)
        assertFalse(failed.isApplicable)
    }

    @Test
    fun triggerFailureIsVisibleAndDoesNotReuseThePreviousSuccessfulResult() {
        val lifecycle = GrScanRequestLifecycle()
        val oldResult = GrScanStatus.parse("complete\n50\n50\n62\n13,16\n0\n1000\nNONE\n")!!
        assertTrue(
            lifecycle.request(processReady = true, startupActive = false, currentStatus = oldResult, nowMillis = 1001)
                is GrScanRequestLifecycle.Decision.Execute
        )

        lifecycle.triggerFailed(1001)
        val status = lifecycle.visibleStatus(oldResult)!!
        assertEquals(GrScanStatus.State.FAILED, status.state)
        assertEquals(1001L, status.scanId)
        assertFalse(status.isApplicable)
    }
}
