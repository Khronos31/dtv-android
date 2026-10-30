package dev.khronos31.mirakc

import java.io.FileDescriptor
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SianoReaderLifecycleTest {
    @Test
    fun brokerCoordinationFencesBeforePublishingAndCancelsItsActualReaderRequest() {
        val closed = AtomicInteger()
        val opened = AtomicInteger()
        val coordination = SianoBrokerReaderCoordination {
            opened.incrementAndGet()
            readerHandle { closed.incrementAndGet() }
        }
        var publishedToken = ""
        coordination.transition("first") { publishedToken = "first" }
        val sockets = connectedSockets()
        val queuedSockets = connectedSockets()
        val leaseReady = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val allowCancellation = CountDownLatch(1)
        val request = Thread {
            coordination.acquire(publishedToken) {
                cancellationEntered.countDown()
                allowCancellation.await(2, TimeUnit.SECONDS)
                sockets.second.close()
            }.use {
                leaseReady.countDown()
                try { sockets.second.getInputStream().read() } catch (_: IOException) { }
            }
        }
        request.start()
        assertTrue(leaseReady.await(2, TimeUnit.SECONDS))
        val queuedFailure = AtomicInteger()
        val queuedRequest = Thread {
            try {
                coordination.acquire("first") { queuedSockets.second.close() }.use { }
            } catch (_: IOException) {
                queuedFailure.incrementAndGet()
            }
        }
        queuedRequest.start()
        awaitBlocked(queuedRequest)
        val replacing = Thread {
            coordination.transition("second") { publishedToken = "second" }
        }
        replacing.start()
        assertTrue(cancellationEntered.await(2, TimeUnit.SECONDS))
        assertEquals("first", publishedToken)
        try {
            coordination.acquire("first") { }
            throw AssertionError("old request was admitted after the generation fence")
        } catch (_: IOException) { }
        allowCancellation.countDown()
        replacing.join(2_000)
        request.join(2_000)
        queuedRequest.join(2_000)
        assertFalse(replacing.isAlive)
        assertFalse(request.isAlive)
        assertFalse(queuedRequest.isAlive)
        assertEquals(1, queuedFailure.get())
        assertEquals("second", publishedToken)
        assertEquals(1, closed.get())
        coordination.acquire("second") { }.close()
        assertEquals(2, opened.get())
        coordination.close()
        assertEquals(2, closed.get())
        sockets.first.close()
        queuedSockets.first.close()
    }

    @Test
    fun generationFenceCancelsBlockedClientBeforeClosingHandleAndReopening() {
        val closed = AtomicInteger()
        val opened = AtomicInteger()
        val lifecycle = SianoReaderLifecycle({
            if (opened.incrementAndGet() == 2) assertEquals(1, closed.get())
            readerHandle { closed.incrementAndGet() }
        })
        lifecycle.transition("first")
        val sockets = connectedSockets()
        val accepted = sockets.second
        val leaseReady = CountDownLatch(1)
        val transitionPaused = CountDownLatch(1)
        val continueTransition = CountDownLatch(1)
        val request = Thread {
            val lease = lifecycle.acquire("first") {
                transitionPaused.countDown()
                continueTransition.await(2, TimeUnit.SECONDS)
                accepted.close()
            }
            leaseReady.countDown()
            try { accepted.getInputStream().read() } catch (_: IOException) { } finally { lease.close() }
        }
        request.start()
        assertTrue(leaseReady.await(2, TimeUnit.SECONDS))
        val replacing = Thread {
            lifecycle.transition("second")
        }
        replacing.start()
        assertTrue(transitionPaused.await(2, TimeUnit.SECONDS))
        try {
            lifecycle.acquire("first") { }
            throw AssertionError("old generation was admitted during invalidation")
        } catch (_: IOException) { }
        continueTransition.countDown()
        replacing.join(2_000)
        request.join(2_000)
        assertFalse(replacing.isAlive)
        assertFalse(request.isAlive)
        assertEquals(1, closed.get())
        val next = lifecycle.acquire("second") { }
        next.close()
        assertEquals(2, opened.get())
        sockets.first.close()
        lifecycle.close()
    }

    @Test
    fun queuedRequestIsCanceledOnTransitionAndFailedOpenRetriesOnlyAfterNewGeneration() {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val lifecycle = SianoReaderLifecycle({
            if (opens.incrementAndGet() == 1) null else readerHandle { closes.incrementAndGet() }
        })
        lifecycle.transition("first")
        try {
            lifecycle.acquire("first") { }
            throw AssertionError("failed reader open was accepted")
        } catch (_: IOException) { }
        lifecycle.transition("second")
        lateinit var lease: SianoReaderLifecycle.Lease
        lease = lifecycle.acquire("second") { lease.close() }
        val queuedSocket = connectedSockets()
        val queuedFailure = AtomicInteger()
        val queuedRequest = Thread {
            try {
                lifecycle.acquire("second") { queuedSocket.second.close() }.use { }
            } catch (_: IOException) {
                queuedFailure.incrementAndGet()
            }
        }
        queuedRequest.start()
        awaitBlocked(queuedRequest)
        lifecycle.transition("third")
        queuedRequest.join(2_000)
        assertFalse(queuedRequest.isAlive)
        assertEquals(1, queuedFailure.get())
        lease.close()
        assertEquals(2, opens.get())
        assertEquals(1, closes.get())
        lifecycle.close()
        assertEquals(1, closes.get())
        queuedSocket.first.close()
    }

    @Test
    fun terminalCloseCancelsSocketBlockedLeaseAndClosesHandleExactlyOnce() {
        val closed = AtomicInteger()
        val lifecycle = SianoReaderLifecycle({ readerHandle { closed.incrementAndGet() } })
        lifecycle.transition("first")
        val sockets = connectedSockets()
        val queuedSockets = connectedSockets()
        val accepted = sockets.second
        val started = CountDownLatch(1)
        val request = Thread {
            val lease = lifecycle.acquire("first") { accepted.close() }
            started.countDown()
            try { accepted.getInputStream().read() } catch (_: IOException) { } finally { lease.close() }
        }
        request.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val queuedFailure = AtomicInteger()
        val queuedRequest = Thread {
            try {
                lifecycle.acquire("first") { queuedSockets.second.close() }.use { }
            } catch (_: IOException) {
                queuedFailure.incrementAndGet()
            }
        }
        queuedRequest.start()
        awaitBlocked(queuedRequest)
        lifecycle.close()
        request.join(2_000)
        queuedRequest.join(2_000)
        assertFalse(request.isAlive)
        assertFalse(queuedRequest.isAlive)
        assertEquals(1, queuedFailure.get())
        assertEquals(1, closed.get())
        sockets.first.close()
        queuedSockets.first.close()
    }

    @Test
    fun staleLeaseReleaseCannotClearTheNextLease() {
        val lifecycle = SianoReaderLifecycle({ readerHandle { } })
        lifecycle.transition("first")
        val first = lifecycle.acquire("first") { }
        first.close()
        val second = lifecycle.acquire("first") { }
        first.close()
        val queued = CountDownLatch(1)
        val third = Thread {
            lifecycle.acquire("first") { }.use { queued.countDown() }
        }
        third.start()
        awaitBlocked(third)
        assertEquals(1L, queued.count)
        second.close()
        assertTrue(queued.await(2, TimeUnit.SECONDS))
        third.join(2_000)
        assertFalse(third.isAlive)
        lifecycle.close()
    }

    private fun connectedSockets(): Pair<Socket, Socket> {
        val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort)
        return client to server.accept().also { server.close() }
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.TIMED_WAITING, thread.state)
    }

    private fun readerHandle(close: () -> Unit) = SianoReaderHandle(1, FileDescriptor(), close)
}
