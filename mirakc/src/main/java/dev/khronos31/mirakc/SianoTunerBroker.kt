package dev.khronos31.mirakc

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.FileDescriptor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One S1UD presents one USB entry (one tuner); support up to four devices. */
internal const val MAX_SIANO_TUNERS = 4

internal data class SianoUsbHandle(val fd: Int, val close: () -> Unit)

/** Android-owned CCID descriptor leased to one decode filter at a time. */
internal class SianoReaderHandle(
    val fd: Int,
    val descriptor: FileDescriptor,
    private val releaser: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaser()
    }
}

internal data class SianoGeneration(
    val token: String,
    val socketName: String,
    val devices: List<SianoDeviceGeneration>
) {
    val tunerCount: Int get() = devices.size
}

internal data class SianoDeviceGeneration(
    val deviceName: String,
    val owner: SianoDaemonOwner,
    val receiver: UserlandReceiver
)

internal interface SianoDaemonOwner {
    val deviceName: String
    val instance: String
    val inventory: UserlandStatusInventory?
    fun start(): Boolean
    fun stop()
}

internal class SianoTsClientLifetime(
    val owner: SianoDaemonOwner,
    private val stopClient: (String) -> Unit
) {
    private val stopped = AtomicBoolean(false)

    fun stop(reason: String) {
        if (stopped.compareAndSet(false, true)) stopClient(reason)
    }
}

internal data class SianoOwnerTicket(val serial: Long)
internal data class SianoOwnerGenerationStart(val ticket: SianoOwnerTicket, val retired: List<SianoDaemonOwner>)

internal class SianoOwnerSet {
    private val lock = Any()
    private var serial = 0L
    private var owners: List<SianoDaemonOwner> = emptyList()

    fun begin(): SianoOwnerGenerationStart = synchronized(lock) {
        val retired = owners
        owners = emptyList()
        serial++
        SianoOwnerGenerationStart(SianoOwnerTicket(serial), retired)
    }

    fun isCurrent(ticket: SianoOwnerTicket): Boolean = synchronized(lock) { serial == ticket.serial }

    fun publish(ticket: SianoOwnerTicket, next: List<SianoDaemonOwner>): Boolean = synchronized(lock) {
        if (serial != ticket.serial) false else {
            owners = next
            true
        }
    }

    fun close(): List<SianoDaemonOwner> = synchronized(lock) {
        serial++
        val retired = owners
        owners = emptyList()
        retired
    }
}

/** Brokers tuner command clients to exactly one Android-owned Siano process. */
internal class SianoTunerBroker(
    private val sianoExecutable: () -> File,
    private val sianodExecutable: () -> File,
    private val ctlExecutable: () -> File,
    private val firmware: () -> File,
    private val runtimeDir: File,
    private val tunerDevices: () -> List<String>,
    private val openTuner: (Int, String) -> SianoUsbHandle,
    private val openReader: () -> SianoReaderHandle?,
    private val ownerFactory: ((Int, String, String, () -> Boolean) -> SianoDaemonOwner)? = null
) : Closeable {
    private val lock = Any()
    private val generationLock = Any()
    private val ownerSet = SianoOwnerSet()
    private val readerCoordination = SianoBrokerReaderCoordination(openReader)
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val pendingClients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val executor = Executors.newFixedThreadPool(MAX_CLIENTS)
    private var server: LocalServerSocket? = null
    private var socketName = ""
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var generation = SianoGeneration("", "", emptyList())

    fun start(): SianoGeneration {
        synchronized(generationLock) {
            synchronized(lock) {
                check(!closed) { "Siano tuner broker is closed" }
                if (!running) {
                    val newSocketName = nextSocketName()
                    val newServer = LocalServerSocket(newSocketName)
                    socketName = newSocketName
                    server = newServer
                    running = true
                    acceptThread = Thread({ acceptLoop() }, "siano-broker-accept").also {
                        it.isDaemon = true
                        it.start()
                    }
                }
            }
            val token = buildToken()
            readerCoordination.transition(token) {
                generation = SianoGeneration(token, socketName, emptyList())
            }
            rotateGenerationLocked(token)
        }
        return generation
    }

    fun rotateGeneration(): SianoGeneration {
        synchronized(generationLock) {
            synchronized(lock) { check(!closed) { "Siano tuner broker is closed" } }
            val token = buildToken()
            readerCoordination.transition(token) {
                generation = SianoGeneration(token, socketName, emptyList())
            }
            rotateGenerationLocked(token)
        }
        return generation
    }

    fun currentGeneration(): SianoGeneration = generation

    override fun close() {
        val currentServer: LocalServerSocket?
        val oldOwners: List<SianoDaemonOwner>
        synchronized(generationLock) {
            readerCoordination.close()
            synchronized(lock) {
                closed = true
                running = false
                currentServer = server
                server = null
                acceptThread?.interrupt()
                acceptThread = null
                sessions.values.toList().forEach { it.stop("broker-close") }
                sessions.clear()
                oldOwners = ownerSet.close()
                generation = SianoGeneration(buildToken(), socketName, emptyList())
            }
        }
        oldOwners.forEach { it.stop() }
        pendingClients.toList().forEach { client ->
            try { client.close() } catch (_: IOException) { }
        }
        try { currentServer?.close() } catch (_: IOException) { }
        executor.shutdownNow()
    }

    private fun rotateGenerationLocked(token: String): SianoGeneration {
        val oldOwners: List<SianoDaemonOwner>
        val ownerStart: SianoOwnerGenerationStart
        val names: List<String>
        synchronized(lock) {
            check(!closed) { "Siano tuner broker is closed" }
            sessions.values.toList().forEach { it.stop("generation-rotate") }
            sessions.clear()
            ownerStart = ownerSet.begin()
            oldOwners = ownerStart.retired
            names = tunerDevices().distinct().take(MAX_SIANO_TUNERS)
            generation = SianoGeneration(token, socketName, emptyList())
        }
        oldOwners.forEach { it.stop() }
        val ready = mutableListOf<SianoDeviceGeneration>()
        names.forEachIndexed { index, name ->
            val instance = "s1-${token.take(12)}-$index"
            val current = { synchronized(lock) { !closed } && ownerSet.isCurrent(ownerStart.ticket) }
            val owner = ownerFactory?.invoke(index, name, instance, current) ?: SianoDeviceOwner(
                index, name, instance, current, sianodExecutable, ctlExecutable, firmware, runtimeDir,
                { deviceIndex, deviceName -> openTuner(deviceIndex, deviceName) },
                { if (current()) Thread({ try { rotateGeneration() } catch (_: Exception) { } }, "siano-owner-reconcile").start() }
            )
            if (owner.start()) {
                val receiver = owner.inventory?.receivers?.singleOrNull { it.id == 0 }
                if (receiver != null && receiver.present && receiver.state !in setOf("absent", "error") &&
                    BroadcastSystem.ISDB_T in receiver.systems && current()) {
                    ready += SianoDeviceGeneration(name, owner, receiver)
                } else {
                    owner.stop()
                }
            }
        }
        val result = synchronized(lock) {
            if (closed || !ownerSet.publish(ownerStart.ticket, ready.map { it.owner })) null
            else {
                generation = SianoGeneration(token, socketName, ready.toList())
                generation
            }
        }
        if (result == null) ready.forEach { it.owner.stop() }
        return result ?: generation
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = server?.accept() ?: return
                try {
                    executor.execute { handle(client) }
                } catch (_: RejectedExecutionException) {
                    try { client.close() } catch (_: IOException) { }
                }
            } catch (_: IOException) {
                if (running) continue else return
            }
        }
    }

    private fun handle(client: LocalSocket) {
        if (!running) {
            try { client.close() } catch (_: IOException) { }
            return
        }
        pendingClients += client
        if (!running) {
            pendingClients -= client
            try { client.close() } catch (_: IOException) { }
            return
        }
        var session: Session? = null
        var stage = "read-request"
        var requestIndex: Int? = null
        var requestChannel: Int? = null
        try {
            client.soTimeout = REQUEST_TIMEOUT_MS
            val request = readRequest(client.inputStream)
            client.soTimeout = 0
            stage = "validate-request"
            val fields = request.trim().split(' ')
            if (fields.size == 2 && fields[0] == "READER") {
                handleReaderRequest(client, fields[1])
                return
            }
            if (fields.size != 4 || fields[0] != PROTOCOL) throw IOException("invalid tuner request")
            val requestToken = fields[1]
            val index = fields[2].toIntOrNull() ?: throw IOException("invalid tuner index")
            val channel = fields[3].toIntOrNull() ?: throw IOException("invalid tuner channel")
            requestIndex = index
            requestChannel = channel
            val current = generation
            if (requestToken != current.token || index !in current.devices.indices ||
                channel !in 13..62) throw IOException("stale or invalid tuner request")
            val candidate = Session(client, index, current.devices[index], channel, current.token)
            stage = "reserve-session"
            synchronized(lock) {
                if (generation.token != current.token || sessions.putIfAbsent(index, candidate) != null) {
                    throw IOException("tuner is busy or generation changed")
                }
                session = candidate
            }
            Log.i(TAG, "tuner request accepted index=$index channel=$channel")
            stage = "run-session"
            candidate.run()
        } catch (error: Exception) {
            val context = buildString {
                append("stage=").append(stage)
                requestIndex?.let { append(" index=").append(it) }
                requestChannel?.let { append(" channel=").append(it) }
            }
            // Never include the request itself or the generation token. The
            // exception type is sufficient to identify the failure class.
            Log.e(TAG, "tuner request failed $context exception=${error.javaClass.simpleName}")
            try { client.close() } catch (_: IOException) { }
        } finally {
            pendingClients -= client
            session?.let {
                sessions.remove(it.index, it)
                it.stop()
            }
        }
    }

    private fun readRequest(input: java.io.InputStream): String {
        val request = ByteArray(256)
        var count = 0
        while (count < request.size) {
            val value = input.read()
            if (value < 0) throw IOException("tuner client closed before request")
            if (value == '\n'.code) return String(request, 0, count, StandardCharsets.US_ASCII)
            request[count++] = value.toByte()
        }
        throw IOException("tuner request is too long")
    }

    private inner class Session(
        private val client: LocalSocket,
        val index: Int,
        private val device: SianoDeviceGeneration,
        private val channel: Int,
        private val token: String
    ) {
        private val stopped = AtomicBoolean(false)
        private val lifecycleLock = Any()
        private var clientLifetime: SianoTsClientLifetime? = null
        private var streamSession: SianoTunerStreamSession? = null

        fun run() {
            val process: NativeUsbProcess.StartedTsClient
            synchronized(lifecycleLock) {
                if (stopped.get()) return
                Log.i(TAG, "starting siano-ts index=$index channel=$channel")
                process = NativeUsbProcess.startTsClient(
                    executable = sianoExecutable().absolutePath,
                    arguments = listOf(
                        "--instance", device.owner.instance,
                        "--runtime-dir", runtimeDir.absolutePath,
                        "--receiver", device.receiver.id.toString(),
                        "--channel", channel.toString()
                    )
                )
                val processOutputClosed = AtomicBoolean(false)
                val closeProcessOutput = {
                    if (processOutputClosed.compareAndSet(false, true)) {
                        try { process.output.close() } catch (_: IOException) { }
                    }
                }
                val diagnosticsClosed = AtomicBoolean(false)
                val closeDiagnostics = {
                    if (diagnosticsClosed.compareAndSet(false, true)) {
                        try { process.diagnostics.close() } catch (_: IOException) { }
                    }
                }
                val processResources = SianoTsProcessResources(
                    stopProcess = { NativeUsbProcess.stopCommand(process.pid) },
                    closeOutput = closeProcessOutput,
                    closeDiagnostics = closeDiagnostics
                )
                val processControlRef = AtomicReference<SianoTsProcessControl?>()
                clientLifetime = SianoTsClientLifetime(device.owner) { reason ->
                    Log.i(TAG, "siano-ts stop stage=$reason index=$index channel=$channel pid=${process.pid}")
                    val control = processControlRef.get()
                    if (control != null) control.stop() else processResources.stop()
                }
                processResources.setup {
                    val processOutput = android.os.ParcelFileDescriptor.AutoCloseInputStream(process.output)
                    val diagnosticsInput = android.os.ParcelFileDescriptor.AutoCloseInputStream(process.diagnostics)
                    val processControl = SianoTsProcessControl(
                        pollProcess = {
                            when (val status = NativeUsbProcess.pollSiano(process.pid)) {
                                NativeUsbProcess.PollResult.ALIVE -> SianoChildStatus.ALIVE
                                is NativeUsbProcess.PollResult.EXITED ->
                                    SianoChildStatus.EXITED(status.code, status.signal)
                                NativeUsbProcess.PollResult.ERROR -> SianoChildStatus.ERROR
                            }
                        },
                        stopProcess = processResources::stop
                    )
                    processControlRef.set(processControl)
                    streamSession = SianoTunerStreamSession(
                        input = processOutput,
                        output = client.outputStream,
                        process = processControl,
                        closeOutput = { try { client.close() } catch (_: IOException) { } }
                    )
                    // Start the sole stderr consumer before releasing the same
                    // lock stop() uses to claim and finish this process.
                    startTsDiagnostics(process, diagnosticsInput, closeDiagnostics)
                    Log.i(TAG, "siano-ts started index=$index channel=$channel pid=${process.pid}")
                }
            }
            val watcher = Thread({ watchClient() }, "siano-broker-watch-$index").also {
                it.isDaemon = true
                it.start()
            }
            try {
                val terminal = streamSession?.run() ?: SianoStreamTerminal(SianoStreamOutcome.CANCEL, 0)
                Log.i(TAG, "siano-ts terminal index=$index channel=$channel outcome=${terminal.outcome} detail=${terminal.detail}")
            } finally {
                watcher.interrupt()
                stop("ts-eof")
            }
        }

        fun stop(reason: String = "stop") {
            val lifetime: SianoTsClientLifetime?
            val stream: SianoTunerStreamSession?
            synchronized(lifecycleLock) {
                if (!stopped.compareAndSet(false, true)) return
                lifetime = clientLifetime
                clientLifetime = null
                stream = streamSession
            }
            try { client.close() } catch (_: IOException) { }
            stream?.cancel()
            lifetime?.stop(reason)
        }

        private fun startTsDiagnostics(
            process: NativeUsbProcess.StartedTsClient,
            input: InputStream,
            closeInput: () -> Unit
        ) {
            Thread({
                try {
                    val buffer = ByteArray(1024)
                    while (input.read(buffer) >= 0) { }
                } catch (_: IOException) { }
                finally { closeInput() }
            }, "siano-ts-diagnostics-${process.pid}").apply { isDaemon = true; start() }
        }

        private fun watchClient() {
            try {
                while (!stopped.get() && client.inputStream.read() >= 0) {
                    // The adapter sends no data after its request; any extra
                    // byte is ignored while keeping the client alive.
                }
            } catch (_: IOException) {
            } finally {
                stop("client-eof")
            }
        }
    }

    /**
     * Handles a decode-filter request for the single CCID reader.
     *
     * The card is one physical resource shared by all decode filters, so the
     * broker serializes access: a filter that arrives while another filter
     * holds the card waits until the holder's socket reaches EOF (the filter
     * keeps its socket open for its whole lifetime).  Tuner sessions never
     * touch the reader; only mirakc's decode-filter does, which means EPG jobs
     * run without the card and viewing streams keep it available.
     */
    private fun handleReaderRequest(client: LocalSocket, token: String) {
        val current = generation
        if (token != current.token) throw IOException("stale reader request")
        val lease = readerCoordination.acquire(current.token) {
            try { client.close() } catch (_: IOException) { }
        }
        try {
            if (generation.token != current.token) throw IOException("generation changed")
            val handle = lease.handle
            client.setFileDescriptorsForSend(arrayOf(handle.descriptor))
            client.outputStream.write(READER_ACK)
            client.outputStream.flush()
            val buffer = ByteArray(256)
            while (!closed && client.inputStream.read(buffer) >= 0) {
                // The filter keeps the socket open until it exits.
            }
        } finally {
            lease.close()
            try { client.close() } catch (_: IOException) { }
        }
    }

    private fun nextSocketName(): String = "mirakc-siano-server-${buildToken()}"

    private fun buildToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val MAX_CLIENTS = 8
        const val PROTOCOL = "SIAO/2"
        const val REQUEST_TIMEOUT_MS = 2_000
        val READER_ACK = byteArrayOf('O'.code.toByte(), 'K'.code.toByte(), '\n'.code.toByte())
        const val TAG = "SianoTunerBroker"
    }
}

private class SianoDeviceOwner(
    private val index: Int,
    override val deviceName: String,
    override val instance: String,
    private val isCurrent: () -> Boolean,
    private val daemonExecutable: () -> File,
    private val ctlExecutable: () -> File,
    private val firmware: () -> File,
    private val runtimeDir: File,
    private val openUsb: (Int, String) -> SianoUsbHandle,
    private val onFailure: () -> Unit
) : SianoDaemonOwner {
    private val lock = Any()
    private var stopped = false
    private var usb: SianoUsbHandle? = null
    private var process: NativeUsbProcess.StartedMirakc? = null
    private var monitor: Thread? = null
    @Volatile override var inventory: UserlandStatusInventory? = null
        private set

    override fun start(): Boolean {
        if (!runtimeDir.isDirectory && !runtimeDir.mkdirs()) return false
        val binary = daemonExecutable()
        val image = firmware()
        if (!binary.isFile || !image.isFile) return false
        val handle = try { openUsb(index, deviceName) } catch (_: Exception) { return false }
        synchronized(lock) {
            if (stopped || !isCurrent()) {
                closeUsb(handle)
                return false
            }
            usb = handle
        }
        val started = try {
            NativeUsbProcess.startCommand(
                binary.absolutePath,
                listOf(
                    "--fd", "3", "--instance", instance,
                    "--runtime-dir", runtimeDir.absolutePath,
                    "--firmware", image.absolutePath,
                    "--pid", "0x2000"
                ),
                firstUsbFd = handle.fd
            )
        } catch (_: Exception) {
            stop()
            return false
        }
        val accepted = synchronized(lock) {
            if (stopped || !isCurrent()) false else {
                process = started
                true
            }
        }
        if (!accepted) {
            NativeUsbProcess.stopCommand(started.pid)
            try { started.output.close() } catch (_: IOException) { }
            stop()
            return false
        }
        drainOutput(started)
        val deadline = System.nanoTime() + READY_TIMEOUT_NS
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
            if (!isCurrent() || synchronized(lock) { stopped }) {
                stop()
                return false
            }
            when (NativeUsbProcess.pollCommand(started.pid)) {
                NativeUsbProcess.PollResult.ALIVE -> Unit
                else -> { stop(); return false }
            }
            try {
                val status = UserlandControl.status(
                    ctlExecutable().absolutePath, runtimeDir.absolutePath, instance, "siano-userland"
                )
                val receiver = status.receivers?.singleOrNull { it.id == 0 }
                if (status.ready && receiver != null && receiver.present &&
                    BroadcastSystem.ISDB_T in receiver.systems) {
                    val ready = synchronized(lock) {
                        if (stopped || !isCurrent()) null else {
                            inventory = status
                            startMonitorLocked(started)
                            status
                        }
                    }
                    if (ready != null) {
                        return true
                    }
                    stop()
                    return false
                }
            } catch (error: IllegalArgumentException) {
                stop()
                return false
            } catch (_: IOException) {
                // The daemon may still be assembling its first complete status snapshot.
            }
            try { Thread.sleep(READY_INTERVAL_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                stop()
                return false
            }
        }
        stop()
        return false
    }

    override fun stop() {
        val current: NativeUsbProcess.StartedMirakc?
        val handle: SianoUsbHandle?
        val monitorThread: Thread?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            inventory = null
            current = process
            process = null
            handle = usb
            usb = null
            monitorThread = monitor
            monitorThread?.interrupt()
            monitor = null
        }
        if (monitorThread != null && monitorThread !== Thread.currentThread()) {
            try { monitorThread.join(MONITOR_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        current?.let {
            NativeUsbProcess.stopCommand(it.pid)
            try { it.output.close() } catch (_: IOException) { }
        }
        handle?.let(::closeUsb)
    }

    private fun startMonitorLocked(started: NativeUsbProcess.StartedMirakc) {
        val thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(MONITOR_INTERVAL_MS) } catch (_: InterruptedException) { return@Thread }
                if (synchronized(lock) { stopped || process !== started }) return@Thread
                if (NativeUsbProcess.pollCommand(started.pid) != NativeUsbProcess.PollResult.ALIVE) {
                    val handle: SianoUsbHandle?
                    synchronized(lock) {
                        if (process !== started) return@Thread
                        process = null
                        inventory = null
                        handle = usb
                        usb = null
                        stopped = true
                    }
                    try { started.output.close() } catch (_: IOException) { }
                    handle?.let(::closeUsb)
                    onFailure()
                    return@Thread
                }
            }
        }, "sianod-monitor-${started.pid}").apply { isDaemon = true }
        if (stopped || process !== started) return
        monitor = thread
        thread.start()
    }

    private fun drainOutput(started: NativeUsbProcess.StartedMirakc) {
        Thread({
            try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(started.output).use { input ->
                    val buffer = ByteArray(2048)
                    while (input.read(buffer) >= 0) { }
                }
            } catch (_: IOException) { }
        }, "sianod-diagnostics-${started.pid}").apply { isDaemon = true; start() }
    }

    private fun closeUsb(handle: SianoUsbHandle) {
        try { handle.close() } catch (_: Exception) { }
    }

    private companion object {
        const val READY_TIMEOUT_NS = 30_000_000_000L
        const val READY_INTERVAL_MS = 100L
        const val MONITOR_INTERVAL_MS = 500L
        const val MONITOR_JOIN_TIMEOUT_MS = 1_000L
    }
}
