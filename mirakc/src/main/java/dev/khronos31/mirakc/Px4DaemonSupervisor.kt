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

/** Owns the USB device or permitted USB pair for one px4d generation. */
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
    val model: Px4DeviceModel,
    val runtimeDir: File,
    val instanceToken: String,
    val tuners: List<Px4TunerPlan>
) {
    fun adapterArguments(receiver: Int): List<String> = listOf(
        "--device=$baseSerial",
        "--instance=$instanceToken",
        "--model=${model.adapterArgument}",
        "--receiver=$receiver"
    )
}

/** Starts px4d only after one enclosure's USB identity has been validated. */
internal interface Px4EnclosureRunner {
    fun startOrGet(): Px4Generation?
    fun stop()
    fun invalidateForDetach()
    fun status(): String
}

private class Px4EnclosureDaemon(
    private val executable: () -> File,
    private val firmware: () -> File,
    private val identities: () -> List<Px4DeviceIdentity>,
    private val openDevice: (Px4DeviceIdentity) -> Px4UsbHandle,
    private val runtimeDir: File,
    private val onStateChanged: () -> Unit,
    private val onDaemonFailure: () -> Unit
) : Px4EnclosureRunner {
    private val lock = java.lang.Object()
    private var process: NativeUsbProcess.StartedPx4d? = null
    private var owners: List<Px4UsbHandle> = emptyList()
    private var monitor: Thread? = null
    private var starting = false
    private var closed = false
    @Volatile private var generation: Px4Generation? = null
    @Volatile private var state = "disabled (PX4 not initialized)"

    override fun startOrGet(): Px4Generation? {
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
        val result = try {
            startGeneration()
        } catch (error: Exception) {
            setState("disabled (PX4 start failed: ${error.message ?: error.javaClass.simpleName})")
            null
        }
        synchronized(lock) {
            starting = false
            lock.notifyAll()
        }
        return result
    }

    override fun stop() {
        var interrupted = false
        synchronized(lock) {
            closed = true
            lock.notifyAll()
            while (starting) {
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        stopCurrent()
        if (interrupted) Thread.currentThread().interrupt()
        setState("stopped")
    }

    /** Invalidate a detached owner's active generation immediately. */
    override fun invalidateForDetach() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        stopCurrent()
        setState("stopped (USB detached)")
    }

    override fun status(): String = state

    private fun startGeneration(): Px4Generation? {
        val selected = selectEnclosure()
        if (selected == null) {
            setState(waitingReason())
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
        // All enclosure daemons share this root; the pinned userland owns and
        // safely cleans each instance's stale sockets and lease files.
        if (!runtimeDir.isDirectory && !runtimeDir.mkdirs()) {
            setState("disabled (cannot create PX4 runtime)")
            return null
        }
        if (!runtimeDir.isDirectory) {
            setState("disabled (cannot create PX4 runtime)")
            return null
        }

        val handles = mutableListOf<Px4UsbHandle>()
        try {
            handles += openDevice(selected.first)
            if (selected.second != null) {
                handles += openDevice(selected.second)
            }
        } catch (error: Exception) {
            handles.forEach { it.close() }
            setState("disabled (USB open failed: ${error.message ?: error.javaClass.simpleName})")
            return null
        }

        val started = try {
            NativeUsbProcess.startPx4d(
                executable = binary.absolutePath,
                firmware = firmwareFile.absolutePath,
                baseSerial = selected.serial,
                instanceToken = selected.instanceToken,
                runtimeDir = runtimeDir.absolutePath,
                firstUsbFd = handles[0].fd,
                secondUsbFd = handles.getOrNull(1)?.fd ?: -1
            )
        } catch (error: Exception) {
            handles.forEach { it.close() }
            setState("disabled (px4d start failed: ${error.message ?: error.javaClass.simpleName})")
            return null
        }
        startDiagnostics(started)

        val ready = try {
            awaitReady(started.pid, selected.instanceToken)
            true
        } catch (error: Exception) {
            Log.e(TAG, "PX4 readiness failed: ${error.message ?: error.javaClass.simpleName}")
            false
        }
        if (!ready) {
            stopStarted(started, handles, "readiness-failure")
            setState("disabled (px4d readiness failed)")
            return null
        }

        val result = Px4Generation(
            selected.serial, selected.model, runtimeDir, selected.instanceToken,
            Px4DeviceSelector.tunersFor(selected)
        )
        return synchronized(lock) {
            if (closed) {
                // Service teardown raced startup; do not publish a generation
                // whose Android descriptors are about to be closed.
                null
            } else {
                process = started
                owners = handles.toList()
                generation = result
                setStateLocked("running (pid ${started.pid})")
                monitor = Thread({ monitorLoop(started, handles.toList()) }, "px4d-monitor-${started.pid}").also {
                    it.isDaemon = true
                    it.start()
                }
                result
            }
        } ?: run {
            stopStarted(started, handles, "stop-during-startup")
            null
        }
    }

    private fun selectEnclosure(): Px4Enclosure? {
        return Px4DeviceSelector.select(identities())
    }

    private fun waitingReason(): String {
        return Px4DeviceSelector.waitingReason(identities())
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

    private fun awaitReady(pid: Int, instanceToken: String) {
        val socket = Px4RuntimeLayout.endpoint(
            runtimeDir, instanceToken, Px4RuntimeLayout.CONTROL_ENDPOINT
        )
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

    private companion object {
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

/** Reconciles one independently-owned px4d process per unambiguous enclosure. */
internal class Px4DaemonSupervisor(
    private val executable: () -> File,
    private val firmware: () -> File,
    private val identities: () -> List<Px4DeviceIdentity>,
    private val openDevice: (Px4DeviceIdentity) -> Px4UsbHandle,
    private val runtimeDir: File,
    private val onStateChanged: () -> Unit,
    private val onDaemonFailure: () -> Unit,
    private val testOwnerFactory: ((Px4Enclosure) -> Px4EnclosureRunner)? = null
) {
    private class IdentitySource(var value: List<Px4DeviceIdentity>)
    private data class Owner(val identitySource: IdentitySource, val daemon: Px4EnclosureRunner)

    private val lock = Any()
    private val owners = linkedMapOf<String, Owner>()
    private val retiring = mutableListOf<Px4EnclosureRunner>()
    @Volatile private var rejectionState: String? = null
    private var closed = false

    fun startAllOrGet(): List<Px4Generation> {
        val plan = Px4DeviceSelector.plan(identities())
        val retired = mutableListOf<Px4EnclosureRunner>()
        val active: List<Px4EnclosureRunner>
        synchronized(lock) {
            if (closed) return emptyList()
            retired += retiring
            retiring.clear()
            rejectionState = plan.rejections.takeIf { it.isNotEmpty() }?.joinToString(", ")
            val wanted = plan.enclosures.associateBy { it.instanceToken }
            owners.toMap().forEach { (token, owner) ->
                val enclosure = wanted[token]
                if (enclosure == null || owner.identitySource.value.sortedBy { it.deviceName } !=
                    enclosure.devices.sortedBy { it.deviceName }) {
                    owners.remove(token)
                    retired += owner.daemon
                }
            }
            plan.enclosures.forEach { enclosure ->
                if (owners.containsKey(enclosure.instanceToken)) return@forEach
                val source = IdentitySource(enclosure.devices)
                val daemon = testOwnerFactory?.invoke(enclosure) ?: Px4EnclosureDaemon(
                    executable = executable,
                    firmware = firmware,
                    identities = { source.value },
                    openDevice = openDevice,
                    runtimeDir = Px4RuntimeLayout.runtimeDirectoryForEnclosure(
                        runtimeDir, enclosure.instanceToken
                    ),
                    onStateChanged = onStateChanged,
                    onDaemonFailure = onDaemonFailure
                )
                owners[enclosure.instanceToken] = Owner(source, daemon)
            }
            active = owners.toSortedMap().values.map { it.daemon }
        }
        retired.forEach { it.stop() }
        return active.mapNotNull { it.startOrGet() }
    }

    /**
     * Consume the detach event against the already-owned device path. Do not
     * wait for a later device-list rescan: Android can reuse that path and
     * serial immediately when the same enclosure is reinserted.
     */
    fun invalidateDetachedDevice(deviceName: String) {
        val detachedOwners = synchronized(lock) {
            val snapshots = owners.map { (token, owner) ->
                Px4OwnerIdentity(token, owner.identitySource.value.mapTo(linkedSetOf()) { it.deviceName })
            }
            val tokens = Px4OwnerDetachPolicy.matchingTokens(snapshots, deviceName)
            val matches = tokens.mapNotNull { token -> owners.remove(token)?.daemon }
            retiring += matches
            matches
        }
        detachedOwners.forEach { it.invalidateForDetach() }
    }

    /** Refreshes the permitted identity set while retaining unchanged daemon owners. */
    fun reconfigure() {
        startAllOrGet()
    }

    fun stop() {
        val retired: List<Px4EnclosureRunner>
        synchronized(lock) {
            closed = true
            retired = owners.values.map { it.daemon } + retiring
            owners.clear()
            retiring.clear()
        }
        retired.forEach { it.stop() }
    }

    fun status(): String {
        val states = synchronized(lock) { owners.toSortedMap().map { (token, owner) -> token to owner.daemon.status() } }
        val rejection = rejectionState
        return buildList {
            if (states.isEmpty()) add(rejection ?: "disabled (PX4 not connected)")
            states.forEach { (token, state) -> add("$token: $state") }
            if (rejection != null && states.isNotEmpty()) add("waiting: $rejection")
        }.joinToString("; ")
    }
}
