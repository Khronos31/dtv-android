package dev.khronos31.mirakc

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal data class Px4DeviceIdentity(val deviceName: String, val serial: String)

/** Owns one permitted PX-Q3U4 pair for the lifetime of a px4d generation. */
internal class Px4UsbHandle(
    val fd: Int,
    private val parcel: ParcelFileDescriptor,
    private val closeConnection: () -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            parcel.close()
        } finally {
            closeConnection()
        }
    }
}

internal data class Px4Generation(
    val baseSerial: String,
    val runtimeDir: File,
    val terrestrialReceivers: List<Int>,
    val satelliteReceivers: List<Int>
)

/** Starts px4d only after pairing and validating the two Android USB owners. */
internal class Px4DaemonSupervisor(
    private val executable: () -> File,
    private val firmware: () -> File,
    private val identities: () -> List<Px4DeviceIdentity>,
    private val openDevice: (Px4DeviceIdentity) -> Px4UsbHandle,
    private val runtimeDir: File,
    private val onStateChanged: () -> Unit,
    private val onDaemonFailure: () -> Unit
) {
    private val lock = java.lang.Object()
    private var process: NativeUsbProcess.StartedPx4d? = null
    private var owners: List<Px4UsbHandle> = emptyList()
    private var monitor: Thread? = null
    private var starting = false
    private var reconfigurePending = false
    private var closed = false
    @Volatile private var generation: Px4Generation? = null
    @Volatile private var state = "disabled (PX4 not initialized)"

    fun startOrGet(): Px4Generation? {
        synchronized(lock) {
            while (starting && !closed) {
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            if (closed) return null
            generation?.let { return it }
            starting = true
        }
        while (true) {
            val result = try {
                startGeneration()
            } catch (error: Exception) {
                setState("disabled (PX4 start failed: ${error.message ?: error.javaClass.simpleName})")
                null
            }
            val rerun = synchronized(lock) {
                val pending = reconfigurePending && !closed
                reconfigurePending = false
                if (!pending) {
                    starting = false
                    lock.notifyAll()
                }
                pending
            }
            if (!rerun) return result
            stopCurrent()
        }
    }

    fun reconfigure() {
        synchronized(lock) {
            if (starting) {
                reconfigurePending = true
                return
            }
        }
        stopCurrent()
        startOrGet()
    }

    fun stop() {
        synchronized(lock) {
            closed = true
            reconfigurePending = false
            lock.notifyAll()
        }
        stopCurrent()
        setState("stopped")
    }

    fun status(): String = state

    private fun startGeneration(): Px4Generation? {
        val pair = pairDevices()
        if (pair == null) {
            setState("waiting (PX4 pair 1/2 not permitted)")
            return null
        }
        val firmwareFile = try {
            validatedFirmware()
        } catch (error: Exception) {
            setState("disabled (firmware ${error.message ?: "invalid"})")
            return null
        }
        val binary = executable()
        if (!binary.isFile) {
            setState("disabled (px4d not packaged)")
            return null
        }
        if (runtimeDir.exists() && !runtimeDir.deleteRecursively()) {
            setState("disabled (cannot clear PX4 runtime)")
            return null
        }
        if (!runtimeDir.mkdirs() && !runtimeDir.isDirectory) {
            setState("disabled (cannot create PX4 runtime)")
            return null
        }

        val first: Px4UsbHandle
        val second: Px4UsbHandle
        try {
            first = openDevice(pair.first)
            second = try {
                openDevice(pair.second)
            } catch (error: Exception) {
                first.close()
                throw error
            }
        } catch (error: Exception) {
            setState("disabled (USB open failed: ${error.message ?: error.javaClass.simpleName})")
            return null
        }

        val started = try {
            NativeUsbProcess.startPx4d(
                executable = binary.absolutePath,
                firmware = firmwareFile.absolutePath,
                baseSerial = pair.base,
                runtimeDir = runtimeDir.absolutePath,
                firstUsbFd = first.fd,
                secondUsbFd = second.fd
            )
        } catch (error: Exception) {
            first.close()
            second.close()
            setState("disabled (px4d start failed: ${error.message ?: error.javaClass.simpleName})")
            return null
        }
        startDiagnostics(started)

        val ready = try {
            awaitReady(started.pid, pair.base)
            true
        } catch (error: Exception) {
            Log.e(TAG, "PX4 readiness failed: ${error.message ?: error.javaClass.simpleName}")
            false
        }
        if (!ready) {
            stopStarted(started, listOf(first, second), "readiness-failure")
            setState("disabled (px4d readiness failed)")
            return null
        }

        val result = Px4Generation(
            pair.base,
            runtimeDir,
            terrestrialReceivers = listOf(2, 3, 6, 7),
            satelliteReceivers = listOf(0, 1, 4, 5)
        )
        return synchronized(lock) {
            if (closed) {
                // Service teardown raced startup; do not publish a generation
                // whose Android descriptors are about to be closed.
                null
            } else {
                process = started
                owners = listOf(first, second)
                generation = result
                setStateLocked("running (pid ${started.pid})")
                monitor = Thread({ monitorLoop(started, listOf(first, second)) }, "px4d-monitor-${started.pid}").also {
                    it.isDaemon = true
                    it.start()
                }
                result
            }
        } ?: run {
            stopStarted(started, listOf(first, second), "stop-during-startup")
            null
        }
    }

    private fun pairDevices(): PairMatch? {
        val parsed = identities().mapNotNull { identity ->
            val match = SERIAL_PATTERN.matchEntire(identity.serial) ?: return@mapNotNull null
            ParsedIdentity(identity, match.groupValues[1], match.groupValues[2].single())
        }
        val matches = parsed.groupBy { it.base }.values.filter { group ->
            group.size == 2 && group.map { it.suffix }.toSet() == setOf('1', '2')
        }
        if (matches.size != 1) return null
        val group = matches.single()
        val first = group.single { it.suffix == '1' }
        val second = group.single { it.suffix == '2' }
        return PairMatch(first.base, first.identity, second.identity)
    }

    private fun validatedFirmware(): File {
        val file = firmware()
        if (!file.isFile) throw IOException("missing")
        if (file.length() != FIRMWARE_SIZE) throw IOException("size")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != FIRMWARE_SHA256) throw IOException("checksum")
        return file
    }

    private fun awaitReady(pid: Int, base: String) {
        val socket = File(runtimeDir, "px4-userland/$base/control.sock")
        val deadline = System.nanoTime() + READY_TIMEOUT_NS
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
            when (NativeUsbProcess.pollPx4d(pid)) {
                // control.sock is a Unix-domain socket, not a regular file;
                // File.isFile() is therefore false for a ready daemon.
                NativeUsbProcess.PollResult.ALIVE -> if (unixSocketExists(socket)) return
                is NativeUsbProcess.PollResult.EXITED -> throw IOException("px4d exited")
                NativeUsbProcess.PollResult.ERROR -> throw IOException("unable to poll px4d")
            }
            try {
                Thread.sleep(READY_INTERVAL_MS)
            } catch (_: InterruptedException) {
                throw IOException("readiness interrupted")
            }
        }
        throw IOException("control socket timeout")
    }

    private fun monitorLoop(started: NativeUsbProcess.StartedPx4d, handles: List<Px4UsbHandle>) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(MONITOR_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val result = synchronized(lock) {
                if (process !== started) return
                NativeUsbProcess.pollPx4d(started.pid)
            }
            if (result == NativeUsbProcess.PollResult.ALIVE) continue
            synchronized(lock) {
                if (process !== started) return
                process = null
                generation = null
                monitor = null
                owners = emptyList()
                setStateLocked("crashed (px4d)")
            }
            finishDiagnostics(started)
            handles.forEach { it.close() }
            onDaemonFailure()
            return
        }
    }

    private fun unixSocketExists(path: File): Boolean = try {
        val mode = Os.stat(path.absolutePath).st_mode
        (mode and OsConstants.S_IFMT) == OsConstants.S_IFSOCK
    } catch (_: ErrnoException) {
        false
    }

    private fun stopCurrent() {
        val current: NativeUsbProcess.StartedPx4d?
        val handles: List<Px4UsbHandle>
        val monitorToJoin: Thread?
        synchronized(lock) {
            current = process
            process = null
            generation = null
            monitorToJoin = monitor
            monitorToJoin?.interrupt()
            monitor = null
            handles = owners
            owners = emptyList()
        }
        if (monitorToJoin != null && monitorToJoin !== Thread.currentThread()) {
            try {
                monitorToJoin.join(MONITOR_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (current != null) {
            val forced = NativeUsbProcess.stopPx4d(current.pid)
            if (forced) Log.w(TAG, "px4d required SIGKILL pid=${current.pid}")
            finishDiagnostics(current)
        }
        handles.forEach { it.close() }
    }

    private fun stopStarted(started: NativeUsbProcess.StartedPx4d, handles: List<Px4UsbHandle>, reason: String) {
        Log.i(TAG, "stopping px4d reason=$reason pid=${started.pid}")
        val forced = NativeUsbProcess.stopPx4d(started.pid)
        if (forced) Log.w(TAG, "px4d required SIGKILL pid=${started.pid}")
        finishDiagnostics(started)
        handles.forEach { it.close() }
    }

    private fun startDiagnostics(started: NativeUsbProcess.StartedPx4d) {
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(started.output).use { input ->
                    val buffer = ByteArray(1024)
                    val line = StringBuilder()
                    var logged = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        for (index in 0 until count) {
                            val value = buffer[index].toInt() and 0xff
                            if (value == '\n'.code) {
                                if (logged < MAX_DIAGNOSTICS_BYTES && line.isNotEmpty()) {
                                    val text = line.toString().take(MAX_DIAGNOSTICS_BYTES - logged)
                                    Log.e(TAG, "px4d $text")
                                    logged += text.length
                                }
                                line.setLength(0)
                            } else if (line.length < MAX_DIAGNOSTIC_LINE) {
                                line.append(if (value in 0x20..0x7e) value.toChar() else '?')
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // Closing the pipe during stop is expected.
            }
        }, "px4d-diagnostics-${started.pid}").apply {
            isDaemon = true
            start()
        }
    }

    private fun finishDiagnostics(started: NativeUsbProcess.StartedPx4d) {
        try {
            started.output.close()
        } catch (_: IOException) {
        }
    }

    private fun setState(value: String) {
        synchronized(lock) { setStateLocked(value) }
    }

    private fun setStateLocked(value: String) {
        state = value
        onStateChanged()
    }

    private data class ParsedIdentity(
        val identity: Px4DeviceIdentity,
        val base: String,
        val suffix: Char
    )

    private data class PairMatch(
        val base: String,
        val first: Px4DeviceIdentity,
        val second: Px4DeviceIdentity
    )

    private companion object {
        val SERIAL_PATTERN = Regex("^(\\d{14})([12])$")
        const val FIRMWARE_SIZE = 2169L
        const val FIRMWARE_SHA256 = "5213a5a38872661277a2cc1b2dfdfe88faf06f41205f460f3b51857f0568b484"
        const val READY_TIMEOUT_NS = 30_000_000_000L
        const val READY_INTERVAL_MS = 100L
        const val MONITOR_INTERVAL_MS = 500L
        const val MONITOR_JOIN_TIMEOUT_MS = 1_000L
        const val MAX_DIAGNOSTICS_BYTES = 64 * 1024
        const val MAX_DIAGNOSTIC_LINE = 512
        const val TAG = "Px4DaemonSupervisor"
    }
}
