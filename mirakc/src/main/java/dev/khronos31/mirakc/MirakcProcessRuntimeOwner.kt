package dev.khronos31.mirakc

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** Process-wide single-owner barrier for Service recreation and long preparation work. */
internal class MirakcProcessRuntimeOwner {
    internal data class Owner internal constructor(
        val generation: Long,
        val token: String = UUID.randomUUID().toString()
    )

    private class OwnerState(val owner: Owner) {
        var stopping = false
        var workCount = 0
        var cleanup: (() -> Unit)? = null
        var cleanupExecutor: Executor? = null
        var cleanupRunning = false
        var cleanupFailure: Throwable? = null
    }

    private val monitor = java.lang.Object()
    private var nextGeneration = 1L
    private var current: OwnerState? = null

    @Throws(InterruptedException::class)
    fun acquireOwner(): Owner {
        synchronized(monitor) {
            while (current != null) monitor.wait()
            return OwnerState(Owner(nextGeneration++)).also { current = it }.owner
        }
    }

    fun isCurrent(owner: Owner, allowStopping: Boolean = false): Boolean =
        synchronized(monitor) {
            current?.let { it.owner == owner && (allowStopping || !it.stopping) } == true
        }

    /** Failure from a revoked owner is visible while a replacement waits. */
    fun blockingFailure(): Throwable? = synchronized(monitor) {
        current?.takeIf { it.stopping }?.cleanupFailure
    }

    /** Retry a failed, idempotent teardown while keeping replacement admission closed. */
    fun retryFailedCleanup(owner: Owner): Boolean {
        val retry = synchronized(monitor) {
            val state = current?.takeIf { it.owner == owner && it.stopping } ?: return false
            if (state.cleanupFailure == null || state.cleanupRunning || state.workCount != 0 || state.cleanup == null) {
                return false
            }
            state.cleanupFailure = null
            true
        }
        if (retry) scheduleCleanup(owner)
        return retry
    }

    /** Explicit recovery hook for a replacement Service waiting behind a failed cleanup. */
    fun retryBlockedCleanup(): Boolean {
        val owner = synchronized(monitor) {
            current?.takeIf { it.stopping && it.cleanupFailure != null }?.owner
        } ?: return false
        return retryFailedCleanup(owner)
    }

    fun acquireWork(owner: Owner): WorkLease? {
        synchronized(monitor) {
            val state = current?.takeIf { it.owner == owner && !it.stopping } ?: return null
            state.workCount += 1
            return WorkLease(this, owner)
        }
    }

    fun beginStop(owner: Owner, executor: Executor, cleanup: () -> Unit) {
        val schedule = synchronized(monitor) {
            val state = current?.takeIf { it.owner == owner } ?: return
            if (state.stopping) return
            state.stopping = true
            state.cleanup = cleanup
            state.cleanupExecutor = executor
            state.workCount == 0
        }
        if (schedule) scheduleCleanup(owner)
    }

    private fun releaseWork(owner: Owner) {
        val schedule = synchronized(monitor) {
            val state = current?.takeIf { it.owner == owner } ?: return
            check(state.workCount > 0) { "unbalanced runtime work lease" }
            state.workCount -= 1
            state.stopping && state.workCount == 0 && state.cleanup != null
        }
        if (schedule) scheduleCleanup(owner)
    }

    private fun scheduleCleanup(owner: Owner) {
        val (executor, cleanup) = synchronized(monitor) {
            val state = current?.takeIf { it.owner == owner && it.stopping && it.workCount == 0 } ?: return
            val work = state.cleanup ?: return
            val targetExecutor = state.cleanupExecutor ?: return
            if (state.cleanupRunning) return
            state.cleanupRunning = true
            targetExecutor to work
        }
        val task = {
            try {
                // A failure leaves this generation revoked and current.
                // Releasing it could admit native work during partial teardown.
                cleanup()
                synchronized(monitor) {
                    if (current?.owner == owner) {
                        stateFor(owner)?.also {
                            it.cleanup = null
                            it.cleanupExecutor = null
                            it.cleanupFailure = null
                            it.cleanupRunning = false
                        }
                        current = null
                    }
                    monitor.notifyAll()
                }
            } catch (error: Throwable) {
                synchronized(monitor) {
                    current?.takeIf { it.owner == owner }?.also {
                        it.cleanupRunning = false
                        it.cleanupFailure = error
                    }
                    monitor.notifyAll()
                }
            }
        }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // A Service may shut down its executor while teardown is queued.
            // Preserve the ownership barrier by running cleanup on a fresh
            // daemon thread instead of admitting a replacement prematurely.
            Thread(task, "mirakc-runtime-owner-cleanup").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun stateFor(owner: Owner): OwnerState? = current?.takeIf { it.owner == owner }

    internal class WorkLease internal constructor(
        private val coordinator: MirakcProcessRuntimeOwner,
        private val owner: Owner
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            coordinator.releaseWork(owner)
        }
    }
}
