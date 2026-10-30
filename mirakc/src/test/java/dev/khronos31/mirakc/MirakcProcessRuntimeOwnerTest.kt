package dev.khronos31.mirakc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirakcProcessRuntimeOwnerTest {
    @Test
    fun trackedFirmwarePreparationMustExitAndCleanupMustFinishBeforeReplacementAdmission() {
        val owners = MirakcProcessRuntimeOwner()
        val first = owners.acquireOwner()
        val preparationLease = owners.acquireWork(first)
        assertNotNull(preparationLease)

        val cleanupEntered = CountDownLatch(1)
        val allowCleanupToFinish = CountDownLatch(1)
        val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "runtime-owner-test-cleanup").apply { isDaemon = true }
        }
        val cleanupSucceeded = java.util.concurrent.atomic.AtomicBoolean(false)
        owners.beginStop(first, cleanupExecutor) {
            cleanupEntered.countDown()
            if (allowCleanupToFinish.await(2, TimeUnit.SECONDS)) cleanupSucceeded.set(true)
        }
        assertFalse(owners.isCurrent(first))
        assertNull(owners.acquireWork(first))

        val replacementEntered = CountDownLatch(1)
        val replacementError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val replacementThread = Thread {
            try {
                val second = owners.acquireOwner()
                replacementEntered.countDown()
                owners.beginStop(second, cleanupExecutor) { }
            } catch (error: Throwable) {
                replacementError.set(error)
            }
        }
        replacementThread.start()

        // The firmware/network worker owns the lease until its real finally;
        // cancellation/interruption alone cannot release process admission.
        assertFalse(replacementEntered.await(100, TimeUnit.MILLISECONDS))
        try {
            preparationLease!!.close()
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            assertFalse(replacementEntered.await(100, TimeUnit.MILLISECONDS))
            allowCleanupToFinish.countDown()
            assertTrue(replacementEntered.await(1, TimeUnit.SECONDS))
            replacementThread.join(1_000)
            assertNull(replacementError.get())
            assertTrue(cleanupSucceeded.get())
        } finally {
            allowCleanupToFinish.countDown()
            preparationLease?.close()
            replacementThread.interrupt()
            replacementThread.join(1_000)
            cleanupExecutor.shutdownNow()
        }
    }

    @Test
    fun repeatedStopAndConcurrentLeaseCloseDoNotReleaseOwnerTwice() {
        val owners = MirakcProcessRuntimeOwner()
        val owner = owners.acquireOwner()
        val lease = owners.acquireWork(owner)!!
        val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "runtime-owner-test-cleanup").apply { isDaemon = true }
        }
        val cleanupCount = java.util.concurrent.atomic.AtomicInteger()
        val cleanupFinished = CountDownLatch(2)
        owners.beginStop(owner, cleanupExecutor) { cleanupCount.incrementAndGet(); cleanupFinished.countDown() }
        owners.beginStop(owner, cleanupExecutor) { error("duplicate cleanup") }
        assertFalse(owners.isCurrent(owner))

        val closers = Executors.newFixedThreadPool(2)
        val done = CountDownLatch(2)
        repeat(2) { closers.execute { lease.close(); done.countDown() } }
        assertTrue(done.await(1, TimeUnit.SECONDS))

        val replacement = owners.acquireOwner()
        assertTrue(replacement.generation > owner.generation)
        owners.beginStop(replacement, cleanupExecutor) { cleanupCount.incrementAndGet(); cleanupFinished.countDown() }
        closers.shutdownNow()
        cleanupExecutor.shutdown()
        assertTrue(cleanupExecutor.awaitTermination(1, TimeUnit.SECONDS))
        assertTrue(cleanupFinished.await(1, TimeUnit.SECONDS))
        assertEquals(2, cleanupCount.get())
    }

    @Test
    fun waitingServiceCanCancelItsAcquisitionWithoutBlockingTheMainCaller() {
        val owners = MirakcProcessRuntimeOwner()
        val first = owners.acquireOwner()
        val firstLease = owners.acquireWork(first)!!
        val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "runtime-owner-test-cleanup").apply { isDaemon = true }
        }
        owners.beginStop(first, cleanupExecutor) { }

        val waitingError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val waiting = Thread {
            try {
                owners.acquireOwner()
            } catch (error: Throwable) {
                waitingError.set(error)
            }
        }
        waiting.start()
        waiting.interrupt()
        waiting.join(1_000)
        assertFalse(waiting.isAlive)
        assertTrue(waitingError.get() is InterruptedException)
        assertTrue(owners.isCurrent(first, allowStopping = true))

        firstLease.close()
        val second = owners.acquireOwner()
        assertTrue(second.generation > first.generation)
        owners.beginStop(second, cleanupExecutor) { }
        cleanupExecutor.shutdown()
        assertTrue(cleanupExecutor.awaitTermination(1, TimeUnit.SECONDS))
    }

    @Test
    fun failedCleanupKeepsAdmissionRevoked() {
        val owners = MirakcProcessRuntimeOwner()
        val owner = owners.acquireOwner()
        val cleanupExecutor = Executor { task ->
            Thread(task, "runtime-owner-failing-cleanup").apply { isDaemon = true }.start()
        }
        val failed = CountDownLatch(1)
        owners.beginStop(owner, cleanupExecutor) {
            failed.countDown()
            throw IllegalStateException("cleanup failed")
        }
        assertTrue(failed.await(1, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (owners.blockingFailure() == null && System.nanoTime() < deadline) Thread.yield()
        assertFalse(owners.isCurrent(owner))
        assertTrue(owners.blockingFailure() is IllegalStateException)
        assertNull(owners.acquireWork(owner))
        // The second acquisition must remain blocked while failed teardown is
        // unresolved. Interrupt its waiter to keep this regression bounded.
        val waitingError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val waiting = Thread {
            try { owners.acquireOwner() } catch (error: Throwable) { waitingError.set(error) }
        }
        waiting.start()
        waiting.interrupt()
        waiting.join(1_000)
        assertTrue(waitingError.get() is InterruptedException)
    }

    @Test
    fun failedCleanupCanBeRetriedWithoutOpeningAdmission() {
        val owners = MirakcProcessRuntimeOwner()
        val owner = owners.acquireOwner()
        val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "runtime-owner-retry-cleanup").apply { isDaemon = true }
        }
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val failed = CountDownLatch(1)
        owners.beginStop(owner, cleanupExecutor) {
            if (attempts.incrementAndGet() == 1) {
                failed.countDown()
                throw IllegalStateException("transient cleanup failure")
            }
        }
        try {
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (owners.blockingFailure() == null && System.nanoTime() < deadline) Thread.yield()
            assertNotNull(owners.blockingFailure())
            assertFalse(owners.isCurrent(owner))
            assertTrue(owners.retryFailedCleanup(owner))
            val replacement = owners.acquireOwner()
            assertEquals(2, attempts.get())
            assertTrue(replacement.generation > owner.generation)
            owners.beginStop(replacement, cleanupExecutor) { }
        } finally {
            cleanupExecutor.shutdown()
            assertTrue(cleanupExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun temporaryOperationMustCloseItsUsbHandleBeforeReplacementAdmission() {
        val owners = MirakcProcessRuntimeOwner()
        val first = owners.acquireOwner()
        val operations = Executors.newSingleThreadExecutor()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val operationStarted = CountDownLatch(1)
        val operationInterrupted = CountDownLatch(1)
        val allowHandleClose = CountDownLatch(1)
        val handleClosed = AtomicBoolean(false)
        operations.execute {
            operationStarted.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                operationInterrupted.countDown()
                allowHandleClose.await()
            } finally {
                handleClosed.set(true)
            }
        }
        assertTrue(operationStarted.await(1, TimeUnit.SECONDS))
        operations.shutdownNow()
        val supervisorStopped = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)
        val replacement = Thread {
            owners.acquireOwner().also {
                replacementEntered.countDown()
                owners.beginStop(it, cleanupExecutor) { }
            }
        }
        owners.beginStop(first, cleanupExecutor) {
            supervisorStopped.countDown()
            RuntimeOperationDrain.awaitTermination(operations, 2_000)
            check(handleClosed.get()) { "USB handle closed after replacement admission" }
        }
        replacement.start()
        try {
            assertTrue(supervisorStopped.await(1, TimeUnit.SECONDS))
            assertTrue(operationInterrupted.await(1, TimeUnit.SECONDS))
            assertFalse(replacementEntered.await(100, TimeUnit.MILLISECONDS))
            allowHandleClose.countDown()
            assertTrue(replacementEntered.await(2, TimeUnit.SECONDS))
            assertTrue(handleClosed.get())
        } finally {
            allowHandleClose.countDown()
            replacement.interrupt()
            replacement.join(1_000)
            operations.shutdownNow()
            cleanupExecutor.shutdownNow()
        }
    }

    @Test
    fun operationDrainTimeoutKeepsReplacementAdmissionBlockedUntilCleanupRetry() {
        val owners = MirakcProcessRuntimeOwner()
        val first = owners.acquireOwner()
        val operations = Executors.newSingleThreadExecutor()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val operationStarted = CountDownLatch(1)
        val allowHandleClose = CountDownLatch(1)
        val handleClosed = AtomicBoolean(false)
        operations.execute {
            operationStarted.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                allowHandleClose.await()
            } finally {
                handleClosed.set(true)
            }
        }
        assertTrue(operationStarted.await(1, TimeUnit.SECONDS))
        operations.shutdownNow()
        val cleanupFinished = CountDownLatch(1)
        owners.beginStop(first, cleanupExecutor) {
            try {
                RuntimeOperationDrain.awaitTermination(operations, 50)
            } finally {
                cleanupFinished.countDown()
            }
        }
        val replacementEntered = CountDownLatch(1)
        val replacement = Thread {
            val next = owners.acquireOwner()
            replacementEntered.countDown()
            owners.beginStop(next, cleanupExecutor) { }
        }
        replacement.start()
        try {
            assertTrue(cleanupFinished.await(1, TimeUnit.SECONDS))
            val failure = owners.blockingFailure()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message?.contains("service operations did not terminate") == true)
            assertFalse(handleClosed.get())
            assertFalse(replacementEntered.await(100, TimeUnit.MILLISECONDS))
            allowHandleClose.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!operations.isTerminated && System.nanoTime() < deadline) Thread.yield()
            assertTrue(handleClosed.get())
            assertTrue(owners.retryFailedCleanup(first))
            assertTrue(replacementEntered.await(1, TimeUnit.SECONDS))
        } finally {
            allowHandleClose.countDown()
            replacement.interrupt()
            replacement.join(1_000)
            operations.shutdownNow()
            cleanupExecutor.shutdownNow()
        }
    }

}
