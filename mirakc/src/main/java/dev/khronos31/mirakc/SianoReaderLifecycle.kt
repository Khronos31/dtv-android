package dev.khronos31.mirakc

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes reader leases with USB permission and broker generations. */
internal class SianoReaderLifecycle(
    private val openReader: () -> SianoReaderHandle?,
    private val waitTimeoutMs: Long = 5_000
) : Closeable {
    internal class Lease internal constructor(
        val handle: SianoReaderHandle,
        private val release: () -> Unit
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private class Request(val cancel: () -> Unit) {
        var canceled = false
    }

    private val lock = Object()
    private val requests = linkedMapOf<Long, Request>()
    private var nextRequest = 0L
    private var activeRequest: Long? = null
    private var generation = ""
    private var handle: SianoReaderHandle? = null
    private var unavailable = false
    private var accepting = false
    private var invalidating = false
    private var closed = false

    fun transition(nextGeneration: String) {
        val cancellations = synchronized(lock) {
            if (closed) throw IOException("reader lifecycle is closed")
            invalidating = true
            accepting = false
            requests.values.forEach { it.canceled = true }
            lock.notifyAll()
            requests.values.map { it.cancel }
        }
        cancellations.forEach { try { it() } catch (_: Exception) { } }
        finishInvalidation(nextGeneration)
    }

    fun acquire(expectedGeneration: String, cancel: () -> Unit): Lease {
        return synchronized(lock) {
            if (!accepting || invalidating || closed || generation != expectedGeneration) {
                throw IOException("reader generation changed")
            }
            val requestId = ++nextRequest
            val request = Request(cancel)
            requests[requestId] = request
            val deadline = System.nanoTime() + waitTimeoutMs * 1_000_000
            try {
                while (activeRequest != null) {
                    checkRequest(request, expectedGeneration)
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw IOException("reader lease wait timed out")
                    try {
                        lock.wait((remaining / 1_000_000).coerceAtLeast(1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("reader lease wait interrupted")
                    }
                }
                checkRequest(request, expectedGeneration)
                val currentHandle = handle ?: if (unavailable) {
                    throw IOException("B-CAS reader is unavailable")
                } else {
                    try {
                        openReader()
                    } catch (_: Exception) {
                        unavailable = true
                        null
                    }?.also { handle = it } ?: run {
                        unavailable = true
                        throw IOException("B-CAS reader is unavailable")
                    }
                }
                checkRequest(request, expectedGeneration)
                activeRequest = requestId
                Lease(currentHandle) { release(requestId) }
            } catch (error: Exception) {
                requests.remove(requestId)
                lock.notifyAll()
                throw error
            }
        }
    }

    override fun close() {
        val cancellations = synchronized(lock) {
            if (closed) return
            closed = true
            invalidating = true
            accepting = false
            requests.values.forEach { it.canceled = true }
            lock.notifyAll()
            requests.values.map { it.cancel }
        }
        cancellations.forEach { try { it() } catch (_: Exception) { } }
        try {
            finishInvalidation(null)
        } catch (_: IOException) {
            // The final lease closes the cached handle when it eventually releases.
        }
    }

    private fun checkRequest(request: Request, expectedGeneration: String) {
        if (request.canceled || !accepting || invalidating || closed || generation != expectedGeneration) {
            throw IOException("reader generation changed")
        }
    }

    private fun finishInvalidation(nextGeneration: String?) {
        val deadline = System.nanoTime() + waitTimeoutMs * 1_000_000
        var stale: SianoReaderHandle? = null
        synchronized(lock) {
            while (requests.isNotEmpty() || activeRequest != null) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("reader leases did not drain")
                try {
                    lock.wait((remaining / 1_000_000).coerceAtLeast(1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("reader lease drain interrupted")
                }
            }
            stale = handle
            handle = null
        }
        try { stale?.close() } catch (_: Exception) { }
        synchronized(lock) {
            unavailable = false
            if (nextGeneration != null && !closed) {
                generation = nextGeneration
                invalidating = false
                accepting = true
            }
            lock.notifyAll()
        }
    }

    private fun release(requestId: Long) {
        var stale: SianoReaderHandle? = null
        synchronized(lock) {
            if (activeRequest == requestId) activeRequest = null
            requests.remove(requestId)
            if (closed && requests.isEmpty() && activeRequest == null) {
                stale = handle
                handle = null
            }
            lock.notifyAll()
        }
        try { stale?.close() } catch (_: Exception) { }
    }
}

/** Couples the broker's published request token to reader admission and draining. */
internal class SianoBrokerReaderCoordination(openReader: () -> SianoReaderHandle?) : Closeable {
    private val lifecycle = SianoReaderLifecycle(openReader)

    fun transition(nextGeneration: String, publishGeneration: () -> Unit) {
        lifecycle.transition(nextGeneration)
        publishGeneration()
    }

    fun acquire(expectedGeneration: String, cancel: () -> Unit): SianoReaderLifecycle.Lease =
        lifecycle.acquire(expectedGeneration, cancel)

    override fun close() = lifecycle.close()
}
