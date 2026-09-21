package dev.khronos31.mirakc

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

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
    val tunerCount: Int,
    val deviceNames: List<String>
)

/** Brokers tuner command clients to exactly one Android-owned Siano process. */
internal class SianoTunerBroker(
    private val sianoExecutable: () -> File,
    private val firmware: () -> File,
    private val tunerDevices: () -> List<String>,
    private val openTuner: (Int, String) -> SianoUsbHandle,
    private val openReader: () -> SianoReaderHandle?
) : Closeable {
    private val lock = Any()
    private val readerLeaseLock = java.lang.Object()
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val pendingClients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val executor = Executors.newFixedThreadPool(MAX_CLIENTS)
    private var server: LocalServerSocket? = null
    private var socketName = ""
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var closed = false
    private var readerHandle: SianoReaderHandle? = null
    private var readerHandleUnavailable = false
    private var readerLeaseHeld = false
    @Volatile private var generation = SianoGeneration("", "", 0, emptyList())

    fun start(): SianoGeneration {
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
            return rotateGenerationLocked()
        }
    }

    fun rotateGeneration(): SianoGeneration {
        synchronized(lock) {
            return rotateGenerationLocked()
        }
    }

    fun currentGeneration(): SianoGeneration = generation

    override fun close() {
        val currentServer: LocalServerSocket?
        synchronized(lock) {
            closed = true
            running = false
            currentServer = server
            server = null
            acceptThread?.interrupt()
            acceptThread = null
            rotateGenerationLocked()
        }
        synchronized(readerLeaseLock) {
            try {
                readerHandle?.close()
            } catch (_: Exception) { }
            readerHandle = null
            readerLeaseLock.notifyAll()
        }
        pendingClients.toList().forEach { client ->
            try { client.close() } catch (_: IOException) { }
        }
        try { currentServer?.close() } catch (_: IOException) { }
        executor.shutdownNow()
    }

    private fun rotateGenerationLocked(): SianoGeneration {
        sessions.values.toList().forEach { it.stop() }
        sessions.clear()
        val token = buildToken()
        val devices = tunerDevices().take(MAX_SIANO_TUNERS)
        generation = SianoGeneration(token, socketName, devices.size, devices)
        return generation
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
            if (requestToken != current.token || index !in 0 until current.tunerCount ||
                channel !in 13..62) throw IOException("stale or invalid tuner request")
            val candidate = Session(client, index, current.deviceNames[index], channel, current.token)
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
        private val deviceName: String,
        private val channel: Int,
        private val token: String
    ) {
        private val stopped = AtomicBoolean(false)
        private val lifecycleLock = Any()
        private var started: NativeUsbProcess.StartedProcess? = null
        private var usb: SianoUsbHandle? = null

        fun run() {
            val handle = openTuner(index, deviceName)
            val process: NativeUsbProcess.StartedProcess
            synchronized(lifecycleLock) {
                if (stopped.get()) {
                    closeHandle(handle)
                    return
                }
                usb = handle
                Log.i(TAG, "starting siano-ts index=$index channel=$channel")
                process = NativeUsbProcess.start(
                    executable = sianoExecutable().absolutePath,
                    firmware = firmware().absolutePath,
                    channel = channel,
                    usbFd = handle.fd
                )
                // stop() is serialized with publication of the child, so a
                // generation rotation cannot miss a just-created process.
                started = process
                // Start the sole stderr consumer before releasing the same
                // lock stop() uses to claim and finish this process.
                NativeUsbProcess.startDiagnostics(process, "tuner=$index, channel=$channel")
                Log.i(TAG, "siano-ts started index=$index channel=$channel pid=${process.pid}")
            }
            val watcher = Thread({ watchClient() }, "siano-broker-watch-$index").also {
                it.isDaemon = true
                it.start()
            }
            try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(process.output).use { input ->
                    val output = client.outputStream
                    val buffer = ByteArray(32 * 1024)
                    while (!stopped.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            output.flush()
                        }
                    }
                }
            } finally {
                watcher.interrupt()
                stop("ts-eof")
            }
        }

        fun stop(reason: String = "stop") {
            val process: NativeUsbProcess.StartedProcess?
            val handle: SianoUsbHandle?
            synchronized(lifecycleLock) {
                if (!stopped.compareAndSet(false, true)) return
                process = started
                started = null
                handle = usb
                usb = null
            }
            process?.let {
                logPoll(it, reason)
                try {
                    NativeUsbProcess.stop(it.pid)
                } finally {
                    try { it.output.close() } catch (_: IOException) { }
                    NativeUsbProcess.finishDiagnostics(it)
                }
            }
            try {
                handle?.close?.invoke()
            } catch (_: Exception) {
                // A failed USB close must not prevent socket/PFD cleanup.
            } finally {
                try { client.close() } catch (_: IOException) { }
            }
        }

        private fun logPoll(process: NativeUsbProcess.StartedProcess, reason: String) {
            when (val result = NativeUsbProcess.pollSiano(process.pid)) {
                NativeUsbProcess.PollResult.ALIVE ->
                    Log.i(TAG, "siano-ts poll stage=$reason index=$index channel=$channel state=ALIVE")
                is NativeUsbProcess.PollResult.EXITED ->
                    Log.i(
                        TAG,
                        "siano-ts poll stage=$reason index=$index channel=$channel " +
                            "state=EXITED code=${result.code ?: "unknown"} signal=${result.signal ?: "none"}"
                    )
                NativeUsbProcess.PollResult.ERROR ->
                    Log.e(TAG, "siano-ts poll stage=$reason index=$index channel=$channel state=ERROR")
            }
        }

        private fun closeHandle(handle: SianoUsbHandle) {
            try { handle.close() } catch (_: Exception) { }
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
        val handle = acquireReaderLease()
        try {
            if (generation.token != current.token) throw IOException("generation changed")
            client.setFileDescriptorsForSend(arrayOf(handle.descriptor))
            client.outputStream.write(READER_ACK)
            client.outputStream.flush()
            val buffer = ByteArray(256)
            while (!closed && client.inputStream.read(buffer) >= 0) {
                // The filter keeps the socket open until it exits.
            }
        } finally {
            releaseReaderLease()
            try { client.close() } catch (_: IOException) { }
        }
    }

    private fun acquireReaderLease(): SianoReaderHandle {
        synchronized(readerLeaseLock) {
            if (readerHandle == null && !readerHandleUnavailable) {
                readerHandle = try {
                    openReader()
                } catch (error: Exception) {
                    readerHandleUnavailable = true
                    null
                }
            }
            val handle = readerHandle ?: throw IOException("B-CAS reader is unavailable")
            while (readerLeaseHeld && !closed) {
                try {
                    readerLeaseLock.wait(1_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("reader lease wait interrupted")
                }
            }
            if (closed) throw IOException("broker is closed")
            readerLeaseHeld = true
            return handle
        }
    }

    private fun releaseReaderLease() {
        synchronized(readerLeaseLock) {
            readerLeaseHeld = false
            readerLeaseLock.notifyAll()
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
        const val PROTOCOL = "SIAO/1"
        const val REQUEST_TIMEOUT_MS = 2_000
        val READER_ACK = byteArrayOf('O'.code.toByte(), 'K'.code.toByte(), '\n'.code.toByte())
        const val TAG = "SianoTunerBroker"
    }
}
