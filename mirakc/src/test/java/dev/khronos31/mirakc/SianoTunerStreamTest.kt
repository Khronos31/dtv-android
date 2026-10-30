package dev.khronos31.mirakc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.net.SocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SianoTunerStreamTest {
    @Test
    fun productionWriterMatchesGoldenFramesConsumedByTheActualAdapter() {
        listOf(
            Triple("exit0.hex", 0, SianoChildStatus.EXITED(0, null)),
            Triple("exit7.hex", 7, SianoChildStatus.EXITED(7, null)),
            Triple("signal9.hex", 137, SianoChildStatus.EXITED(null, 9))
        ).forEach { (fixture, expectedExit, childStatus) ->
            val expectedFrames = golden(fixture)
            val host = ProcessBuilder(hostAdapter()).start()
            val captured = ByteArrayOutputStream()
            val adapterInput = host.outputStream
            val output = tee(adapterInput, captured)
            val terminal = SianoTunerStreamSession(
                input = ByteArrayInputStream(TS_BYTES),
                output = output,
                process = processControl(childStatus),
                closeOutput = { adapterInput.close() }
            ).run()

            output.close()
            assertEquals(expectedExit, terminal.adapterExitCode())
            assertArrayEquals(expectedFrames, captured.toByteArray())
            assertTrue(host.waitFor(2, TimeUnit.SECONDS))
            assertEquals(expectedExit, host.exitValue())
            assertArrayEquals(TS_BYTES, host.inputStream.readBytes())
        }
    }

    @Test
    fun syntheticChildExitStatusesReachTheActualAdapter() {
        listOf(0, 7).forEach { exitCode ->
            val child = ProcessBuilder(
                "/bin/sh", "-c", "printf '\\107\\000\\000\\020'; exit $exitCode"
            ).start()
            val host = ProcessBuilder(hostAdapter()).start()
            val captured = ByteArrayOutputStream()
            val adapterInput = host.outputStream
            val resources = processResources(child)
            val process = SianoTsProcessControl(
                pollProcess = { childStatus(child) },
                stopProcess = resources::stop
            )
            val terminal = SianoTunerStreamSession(
                input = child.inputStream,
                output = tee(adapterInput, captured),
                process = process,
                closeOutput = { adapterInput.close() }
            )

            try {
                val result = terminal.run()
                adapterInput.close()
                assertTrue(host.waitFor(2, TimeUnit.SECONDS))
                assertEquals(exitCode, result.adapterExitCode())
                assertEquals(exitCode, host.exitValue())
                assertArrayEquals(golden(if (exitCode == 0) "exit0.hex" else "exit7.hex"), captured.toByteArray())
                assertArrayEquals(TS_BYTES, host.inputStream.readBytes())
            } finally {
                process.stop()
                resources.stop()
                if (host.isAlive) host.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
                host.inputStream.close()
                host.errorStream.close()
            }
        }
    }

    @Test
    fun syntheticChildThatClosesStdoutButStaysAliveTimesOutAndIsReaped() {
        val child = ProcessBuilder(
            "/bin/sh", "-c", "printf '\\107\\000\\000\\020'; exec 1>&-; exec sleep 30"
        ).start()
        val host = ProcessBuilder(hostAdapter()).start()
        val adapterInput = host.outputStream
        val resources = processResources(child)
        val process = SianoTsProcessControl(
            pollProcess = { childStatus(child) },
            stopProcess = resources::stop
        )
        val session = SianoTunerStreamSession(
            input = child.inputStream,
            output = adapterInput,
            process = process,
            closeOutput = { adapterInput.close() },
            statusTimeoutMs = 150,
            pollIntervalMs = 5
        )

        try {
            val started = System.nanoTime()
            val terminal = session.run()
            adapterInput.close()
            assertEquals(SianoStreamOutcome.TIMEOUT, terminal.outcome)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            assertTrue(host.waitFor(2, TimeUnit.SECONDS))
            assertEquals(1, host.exitValue())
            assertArrayEquals(TS_BYTES, host.inputStream.readBytes())
        } finally {
            process.stop()
            resources.stop()
            if (host.isAlive) host.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
            host.inputStream.close()
            host.errorStream.close()
        }
        assertFalse(child.isAlive)
    }

    @Test
    fun signalTerminatedSyntheticChildReachesAdapterAsFailure() {
        val child = ProcessBuilder(
            "/bin/sh", "-c", "printf '\\107\\000\\000\\020'; kill -TERM $$"
        ).start()
        val host = ProcessBuilder(hostAdapter()).start()
        val captured = ByteArrayOutputStream()
        val adapterInput = host.outputStream
        val resources = processResources(child)
        val process = SianoTsProcessControl(
            pollProcess = { childStatus(child) },
            stopProcess = resources::stop
        )
        val session = SianoTunerStreamSession(
            input = child.inputStream,
            output = tee(adapterInput, captured),
            process = process,
            closeOutput = { adapterInput.close() }
        )

        try {
            val terminal = session.run()
            adapterInput.close()
            assertTrue(host.waitFor(2, TimeUnit.SECONDS))
            assertTrue(terminal.adapterExitCode() != 0)
            assertTrue(host.exitValue() != 0)
            assertArrayEquals(TS_BYTES, host.inputStream.readBytes())
        } finally {
            process.stop()
            resources.stop()
            if (host.isAlive) host.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
            host.inputStream.close()
            host.errorStream.close()
        }
        assertFalse(child.isAlive)
    }

    @Test
    fun postSpawnSetupFailureStopsChildAndClosesBothPipesOnce() {
        val child = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val outputClosed = AtomicInteger()
        val diagnosticsClosed = AtomicInteger()
        val ownerStops = AtomicInteger()
        val terminalBytes = ByteArrayOutputStream()
        val streamCreated = AtomicBoolean(false)
        val injectSetupFailure = AtomicBoolean(true)
        val owner = object : SianoDaemonOwner {
            override val deviceName = "synthetic"
            override val instance = "synthetic-instance"
            override val inventory: UserlandStatusInventory? = null
            override fun start() = true
            override fun stop() { ownerStops.incrementAndGet() }
        }
        val resources = processResources(
            child,
            closeOutput = { child.inputStream.close(); outputClosed.incrementAndGet() },
            closeDiagnostics = { child.errorStream.close(); diagnosticsClosed.incrementAndGet() }
        )
        val lifetime = SianoTsClientLifetime(owner) { resources.stop() }
        val setupAction: () -> SianoTunerStreamSession = {
            if (injectSetupFailure.getAndSet(false)) throw IOException("injected post-spawn stream setup failure")
            SianoTunerStreamSession(
                input = child.inputStream,
                output = terminalBytes,
                process = SianoTsProcessControl({ childStatus(child) }, resources::stop),
                closeOutput = {}
            ).also { streamCreated.set(true) }
        }
        val caught = try {
            resources.setup(setupAction)
            null
        } catch (error: IOException) {
            error
        } finally {
            lifetime.stop("setup-failure")
            lifetime.stop("setup-failure-retry")
        }

        assertEquals("injected post-spawn stream setup failure", caught?.message)
        assertFalse(child.isAlive)
        assertTrue(child.waitFor(1, TimeUnit.SECONDS))
        assertEquals(1, outputClosed.get())
        assertEquals(1, diagnosticsClosed.get())
        assertEquals(0, ownerStops.get())
        assertFalse(streamCreated.get())
        assertEquals(0, terminalBytes.size())
    }

    @Test
    fun realUnixSocketBackpressureIsUnblockedByCancellationAndReapsChild() {
        val directory = Files.createTempDirectory("siano-stream-uds")
        val socketPath = directory.resolve("stream.sock")
        var server: ServerSocketChannel? = null
        var client: SocketChannel? = null
        var peer: SocketChannel? = null
        var acceptThread: Thread? = null
        var child: Process? = null
        var process: SianoTsProcessControl? = null
        var session: SianoTunerStreamSession? = null
        var worker: Thread? = null
        try {
            val addressClass = Class.forName("java.net.UnixDomainSocketAddress")
            val address = addressClass.getMethod("of", java.nio.file.Path::class.java).invoke(null, socketPath)
            val socketAddress = address as SocketAddress
            val family = StandardProtocolFamily.UNIX
            val familyType = Class.forName("java.net.ProtocolFamily")
            val serverChannel = ServerSocketChannel::class.java
                .getMethod("open", familyType)
                .invoke(null, family) as ServerSocketChannel
            server = serverChannel
            serverChannel.bind(socketAddress)
            val accepted = AtomicReference<SocketChannel?>()
            val accepting = Thread { accepted.set(serverChannel.accept()) }.apply { isDaemon = true; start() }
            acceptThread = accepting
            val clientChannel = SocketChannel::class.java
                .getMethod("open", familyType)
                .invoke(null, family) as SocketChannel
            client = clientChannel
            clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 4096)
            clientChannel.connect(socketAddress)
            accepting.join(1_000)
            val peerChannel = accepted.get() ?: throw AssertionError("UDS accept did not complete")
            peer = peerChannel
            val syntheticChild = ProcessBuilder("/bin/sh", "-c", "exec head -c 67108864 /dev/zero").start()
            child = syntheticChild
            val resources = processResources(syntheticChild)
            val processControl = SianoTsProcessControl(
                pollProcess = { childStatus(syntheticChild) },
                stopProcess = resources::stop
            )
            process = processControl
            val streamSession = SianoTunerStreamSession(
                input = syntheticChild.inputStream,
                output = Channels.newOutputStream(clientChannel),
                process = processControl,
                closeOutput = { clientChannel.close() },
                statusTimeoutMs = 500,
                pollIntervalMs = 5
            )
            session = streamSession
            val terminal = AtomicReference<SianoStreamTerminal>()
            val writer = Thread { terminal.set(streamSession.run()) }.apply { isDaemon = true }
            worker = writer
            writer.start()
            Thread.sleep(200)
            assertTrue("writer should remain blocked by the unread UDS peer", writer.isAlive)
            val started = System.nanoTime()
            streamSession.cancel()
            processControl.stop()
            writer.join(1_500)

            assertFalse("writer did not unblock after closing the UDS", writer.isAlive)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
            assertEquals(SianoStreamOutcome.CANCEL, terminal.get().outcome)
            assertFalse(syntheticChild.isAlive)
        } finally {
            session?.cancel()
            process?.stop()
            child?.let {
                if (it.isAlive) it.destroyForcibly()
                it.waitFor(1, TimeUnit.SECONDS)
                it.inputStream.close()
                it.errorStream.close()
            }
            worker?.let {
                if (it.isAlive) it.interrupt()
                it.join(1_000)
            }
            client?.close()
            peer?.close()
            server?.close()
            acceptThread?.join(1_000)
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun stdoutEofWhileChildAliveProducesBoundedTimeoutFailure() {
        val host = ProcessBuilder(hostAdapter()).start()
        val captured = ByteArrayOutputStream()
        val adapterInput = host.outputStream
        val session = SianoTunerStreamSession(
            input = ByteArrayInputStream(ByteArray(0)),
            output = tee(adapterInput, captured),
            process = SianoTsProcessControl({ SianoChildStatus.ALIVE }, {}),
            closeOutput = { adapterInput.close() },
            statusTimeoutMs = 40,
            pollIntervalMs = 2
        )

        val started = System.nanoTime()
        val terminal = session.run()
        adapterInput.close()

        assertEquals(SianoStreamOutcome.TIMEOUT, terminal.outcome)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
        assertTrue(host.waitFor(2, TimeUnit.SECONDS))
        assertEquals(1, host.exitValue())
        assertArrayEquals(ByteArray(0), host.inputStream.readBytes())
    }

    @Test
    fun unknownOrErrorChildStatusCannotBecomeSuccess() {
        listOf(
            SianoChildStatus.ERROR,
            SianoChildStatus.EXITED(null, null),
            SianoChildStatus.EXITED(256, null),
            SianoChildStatus.EXITED(0, 9)
        ).forEach { childStatus ->
            val host = ProcessBuilder(hostAdapter()).start()
            val captured = ByteArrayOutputStream()
            val expectedFrames = golden("exit7.hex").also {
                it[31] = SianoStreamOutcome.POLL_ERROR.wireValue.toByte()
                it[35] = 0
            }
            val adapterInput = host.outputStream
            val terminal = SianoTunerStreamSession(
                input = ByteArrayInputStream(TS_BYTES),
                output = tee(adapterInput, captured),
                process = processControl(childStatus),
                closeOutput = { adapterInput.close() }
            ).run()
            adapterInput.close()

            assertEquals(SianoStreamOutcome.POLL_ERROR, terminal.outcome)
            assertTrue(host.waitFor(2, TimeUnit.SECONDS))
            assertEquals(1, host.exitValue())
            assertArrayEquals(TS_BYTES, host.inputStream.readBytes())
            assertArrayEquals(expectedFrames, captured.toByteArray())
        }
    }

    @Test
    fun cancellationClosesClientAndStopsChildWithoutWritingSuccessEnd() {
        val host = ProcessBuilder(hostAdapter()).start()
        val adapterInput = host.outputStream
        val dataWritten = CountDownLatch(1)
        val input = BlockingInputStream()
        val output = object : OutputStream() {
            override fun write(value: Int) = adapterInput.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                adapterInput.write(bytes, offset, length)
                if (length == TS_BYTES.size) dataWritten.countDown()
            }
            override fun close() = adapterInput.close()
        }
        val process = SianoTsProcessControl({ SianoChildStatus.ALIVE }, { input.close() })
        val session = SianoTunerStreamSession(
            input = input,
            output = output,
            process = process,
            closeOutput = { output.close() },
            statusTimeoutMs = 100,
            pollIntervalMs = 2
        )
        val terminal = AtomicReference<SianoStreamTerminal>()
        val worker = Thread { terminal.set(session.run()) }.apply { isDaemon = true }
        val started = System.nanoTime()
        worker.start()
        try {
            assertTrue(dataWritten.await(2, TimeUnit.SECONDS))
            session.cancel()
            process.stop()
            worker.join(1_000)

            assertFalse(worker.isAlive)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
            assertEquals(SianoStreamOutcome.CANCEL, terminal.get().outcome)
            assertTrue(host.waitFor(2, TimeUnit.SECONDS))
            assertTrue(host.exitValue() != 0)
            assertArrayEquals(TS_BYTES, host.inputStream.readBytes())
        } finally {
            session.cancel()
            process.stop()
            if (worker.isAlive) worker.interrupt()
            worker.join(1_000)
            adapterInput.close()
            if (host.isAlive) host.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
            host.inputStream.close()
            host.errorStream.close()
        }
    }

    @Test
    fun cancellationUnblocksAStalledDownstreamWrite() {
        val host = ProcessBuilder(hostAdapter()).start()
        val adapterInput = host.outputStream
        val writeStarted = CountDownLatch(1)
        val writeReleased = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) = adapterInput.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                adapterInput.write(bytes, offset, length)
                if (length == TS_BYTES.size) {
                    writeStarted.countDown()
                    if (!writeReleased.await(2, TimeUnit.SECONDS)) throw IOException("downstream remained blocked")
                    throw IOException("downstream closed")
                }
            }
            override fun close() {
                adapterInput.close()
                writeReleased.countDown()
            }
        }
        val process = SianoTsProcessControl({ SianoChildStatus.ALIVE }, {})
        val session = SianoTunerStreamSession(
            input = ByteArrayInputStream(TS_BYTES),
            output = output,
            process = process,
            closeOutput = { output.close() }
        )
        val terminal = AtomicReference<SianoStreamTerminal>()
        val worker = Thread { terminal.set(session.run()) }.apply { isDaemon = true }
        val started = System.nanoTime()
        worker.start()
        try {
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
            session.cancel()
            process.stop()
            worker.join(1_000)

            assertFalse(worker.isAlive)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
            assertEquals(SianoStreamOutcome.CANCEL, terminal.get().outcome)
            assertTrue(host.waitFor(2, TimeUnit.SECONDS))
            assertTrue(host.exitValue() != 0)
            assertArrayEquals(TS_BYTES, host.inputStream.readBytes())
        } finally {
            session.cancel()
            process.stop()
            if (worker.isAlive) worker.interrupt()
            worker.join(1_000)
            adapterInput.close()
            if (host.isAlive) host.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
            host.inputStream.close()
            host.errorStream.close()
        }
    }

    @Test
    fun downstreamFailureCannotBeReportedAsSuccess() {
        val host = ProcessBuilder(hostAdapter()).start()
        val adapterInput = host.outputStream
        val output = object : OutputStream() {
            override fun write(value: Int) = adapterInput.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length == TS_BYTES.size) {
                    adapterInput.close()
                    throw IOException("downstream closed")
                }
                adapterInput.write(bytes, offset, length)
            }
        }
        val session = SianoTunerStreamSession(
            input = ByteArrayInputStream(TS_BYTES),
            output = output,
            process = processControl(SianoChildStatus.EXITED(0, null)),
            closeOutput = { adapterInput.close() }
        )

        val terminal = session.run()

        assertEquals(SianoStreamOutcome.BROKER_IO, terminal.outcome)
        assertTrue(host.waitFor(2, TimeUnit.SECONDS))
        assertTrue(host.exitValue() != 0)
        assertArrayEquals(ByteArray(0), host.inputStream.readBytes())
    }

    private fun processControl(status: SianoChildStatus) =
        SianoTsProcessControl({ status }, {})

    private fun childStatus(child: Process): SianoChildStatus =
        if (child.isAlive) SianoChildStatus.ALIVE else SianoChildStatus.EXITED(child.exitValue(), null)

    private fun processResources(
        child: Process,
        closeOutput: () -> Unit = { child.inputStream.close() },
        closeDiagnostics: () -> Unit = { child.errorStream.close() }
    ) = SianoTsProcessResources(
        stopProcess = {
            if (child.isAlive) child.destroy()
            if (!child.waitFor(500, TimeUnit.MILLISECONDS)) child.destroyForcibly()
            child.waitFor(1, TimeUnit.SECONDS)
        },
        closeOutput = closeOutput,
        closeDiagnostics = closeDiagnostics
    )

    private fun tee(target: OutputStream, capture: ByteArrayOutputStream) = object : OutputStream() {
        override fun write(value: Int) {
            capture.write(value)
            target.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            capture.write(bytes, offset, length)
            target.write(bytes, offset, length)
        }

        override fun flush() = target.flush()
        override fun close() = target.close()
    }

    private fun golden(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/siano-terminal/$name")
            ?: throw AssertionError("missing shared SIAO/2 fixture $name")
        return stream.bufferedReader().use { reader ->
            reader.readText().trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    private fun hostAdapter(): String {
        val path = System.getProperty("mirakc.siano.terminal.host")
            ?: throw AssertionError("Gradle did not provide the actual Siano adapter host runner")
        assertTrue("Siano adapter host runner does not exist: $path", java.io.File(path).isFile)
        return path
    }

    private class BlockingInputStream : InputStream() {
        private val monitor = Object()
        private var offset = 0
        private var closed = false

        override fun read(bytes: ByteArray, start: Int, length: Int): Int = synchronized(monitor) {
            if (offset < TS_BYTES.size) {
                val count = minOf(length, TS_BYTES.size - offset)
                TS_BYTES.copyInto(bytes, start, offset, offset + count)
                offset += count
                return@synchronized count
            }
            while (!closed) {
                try {
                    monitor.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted")
                }
            }
            -1
        }

        override fun read(): Int {
            val bytes = ByteArray(1)
            return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 0xff
        }

        override fun close() {
            synchronized(monitor) {
                closed = true
                monitor.notifyAll()
            }
        }
    }

    private companion object {
        val TS_BYTES = byteArrayOf(0x47, 0x00, 0x00, 0x10)
    }
}
