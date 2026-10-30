package dev.khronos31.mirakc

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException

internal interface UserlandOwnedUsb : Closeable {
    val fd: Int
}

internal data class UserlandDaemonProcess(val pid: Int, val output: ParcelFileDescriptor?)

internal interface UserlandOwnerBackend {
    fun open(device: UserlandUsbIdentity): UserlandOwnedUsb
    fun start(product: UserlandProduct, instance: String, runtimeDir: File, handles: List<UserlandOwnedUsb>): UserlandDaemonProcess
    fun status(product: UserlandProduct, instance: String, runtimeDir: File): UserlandStatusInventory
    fun poll(pid: Int): NativeUsbProcess.PollResult
    fun stop(pid: Int)
}

internal class NativeUserlandOwnerBackend(
    private val openUsb: (UserlandUsbIdentity) -> UserlandOwnedUsb,
    private val executable: (UserlandProduct) -> File,
    private val ctlExecutable: (UserlandProduct) -> File
) : UserlandOwnerBackend {
    override fun open(device: UserlandUsbIdentity): UserlandOwnedUsb = openUsb(device)

    override fun start(
        product: UserlandProduct,
        instance: String,
        runtimeDir: File,
        handles: List<UserlandOwnedUsb>
    ): UserlandDaemonProcess {
        require(handles.isNotEmpty() && handles.size <= 2)
        val args = mutableListOf<String>()
        handles.indices.forEach { index -> args += listOf("--fd", (index + 3).toString()) }
        args += listOf("--instance", instance, "--runtime-dir", runtimeDir.absolutePath, "--group")
        val process = NativeUsbProcess.startCommand(
            executable(product).absolutePath, args, handles[0].fd, handles.getOrNull(1)?.fd ?: -1
        )
        return UserlandDaemonProcess(process.pid, process.output)
    }

    override fun status(product: UserlandProduct, instance: String, runtimeDir: File): UserlandStatusInventory =
        UserlandControl.status(ctlExecutable(product).absolutePath, runtimeDir.absolutePath, instance, product.statusProduct, group = true)

    override fun poll(pid: Int): NativeUsbProcess.PollResult = NativeUsbProcess.pollCommand(pid)
    override fun stop(pid: Int) = NativeUsbProcess.stopCommand(pid)
}

internal class UserlandEnclosureOwner(
    private val product: UserlandProduct,
    private val enclosure: UserlandEnclosure,
    private val runtimeDir: File,
    private val backend: UserlandOwnerBackend,
    private val onFailure: () -> Unit = {},
    private val readyTimeoutMs: Long = 30_000,
    private val pollIntervalMs: Long = 100
) {
    private val lock = Object()
    private var generationTicket = 1L
    private var starting = false
    private var retired = false
    private var process: UserlandDaemonProcess? = null
    private var handles: List<UserlandOwnedUsb> = emptyList()
    private var generation: UserlandGeneration? = null

    fun startOrGet(): UserlandGeneration? {
        val ticket: Long
        synchronized(lock) {
            while (starting && !retired) {
                try { lock.wait() } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            if (retired) return null
            generation?.let { return it }
            starting = true
            ticket = generationTicket
        }
        val result = try { startGeneration(ticket) } catch (_: Exception) { null }
        synchronized(lock) {
            starting = false
            lock.notifyAll()
        }
        return result
    }

    fun invalidateForDetach(deviceName: String) {
        if (enclosure.devices.none { it.deviceName == deviceName }) return
        retireOwner()
    }

    fun stop() {
        retireOwner()
    }

    private fun retireOwner() {
        var interrupted = false
        synchronized(lock) {
            generationTicket++
            retired = true
            lock.notifyAll()
            while (starting) {
                try { lock.wait() } catch (_: InterruptedException) { interrupted = true }
            }
        }
        retireCurrent()
        if (interrupted) Thread.currentThread().interrupt()
    }

    fun owns(deviceName: String): Boolean = enclosure.devices.any { it.deviceName == deviceName }

    fun isRetired(): Boolean = synchronized(lock) { retired }

    private fun startGeneration(ticket: Long): UserlandGeneration? {
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) throw IOException("cannot create private runtime")
        if (!runtimeDir.isDirectory) throw IOException("invalid private runtime")
        val opened = mutableListOf<UserlandOwnedUsb>()
        var started: UserlandDaemonProcess? = null
        try {
            enclosure.devices.forEach { opened += backend.open(it) }
            started = backend.start(product, enclosure.token, runtimeDir, opened)
            drainOutput(started.output)
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readyTimeoutMs)
            var inventory: UserlandStatusInventory? = null
            var observedEpoch: String? = null
            while (System.nanoTime() < deadline) {
                synchronized(lock) { if (generationTicket != ticket || retired) throw IOException("owner generation retired") }
                when (backend.poll(started.pid)) {
                    NativeUsbProcess.PollResult.ALIVE -> try {
                        val status = backend.status(product, enclosure.token, runtimeDir)
                        if (observedEpoch == null) observedEpoch = status.epoch
                        else if (observedEpoch != status.epoch) throw EpochChangedException()
                        if (status.product == product.statusProduct && status.instance == enclosure.token &&
                            status.ready && status.expectedFunctionCount == enclosure.devices.size
                        ) {
                            inventory = status
                            break
                        }
                    } catch (error: EpochChangedException) {
                        throw IOException("${product.label} daemon epoch changed during startup", error)
                    } catch (_: Exception) { }
                    is NativeUsbProcess.PollResult.EXITED -> throw IOException("${product.label} daemon exited before ready")
                    NativeUsbProcess.PollResult.ERROR -> throw IOException("unable to poll ${product.label} daemon")
                }
                Thread.sleep(pollIntervalMs)
            }
            val ready = inventory ?: throw IOException("${product.label} readiness timed out")
            val value = UserlandGeneration(
                product, enclosure.token, runtimeDir, ready.epoch,
                enclosure.devices.mapTo(linkedSetOf()) { it.deviceKey },
                userlandTunersFromInventory(product, enclosure.token, ready)
            )
            synchronized(lock) {
                if (generationTicket != ticket || retired) throw IOException("owner generation retired")
                process = started
                handles = opened.toList()
                generation = value
            }
            startMonitor(started)
            return value
        } catch (error: Exception) {
            started?.let { backend.stop(it.pid); try { it.output?.close() } catch (_: Exception) { } }
            opened.asReversed().forEach { runCatching { it.close() } }
            throw error
        }
    }

    private fun drainOutput(output: ParcelFileDescriptor?) {
        if (output == null) return
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
                    val buffer = ByteArray(4096)
                    var retained = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        retained = (retained + count).coerceAtMost(MAX_DIAGNOSTIC_BYTES)
                    }
                }
            } catch (_: IOException) { }
        }, "${product.label.lowercase()}-diagnostics-${enclosure.token}").apply { isDaemon = true; start() }
    }

    private fun startMonitor(started: UserlandDaemonProcess) {
        Thread({
            while (true) {
                val result = backend.poll(started.pid)
                when (result) {
                    NativeUsbProcess.PollResult.ALIVE -> try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                    else -> {
                        if (result == NativeUsbProcess.PollResult.ERROR) backend.stop(started.pid)
                        val current = synchronized(lock) {
                            if (process?.pid != started.pid) false else {
                                process = null
                                generation = null
                                generationTicket++
                                true
                            }
                        }
                        if (current) {
                            try { started.output?.close() } catch (_: Exception) { }
                            synchronized(lock) { handles.asReversed().forEach { runCatching { it.close() } }; handles = emptyList() }
                            onFailure()
                        }
                        return@Thread
                    }
                }
            }
        }, "${product.label.lowercase()}-owner-${enclosure.token}").apply { isDaemon = true; start() }
    }

    private fun retireCurrent() {
        val current: UserlandDaemonProcess?
        val opened: List<UserlandOwnedUsb>
        synchronized(lock) {
            current = process
            opened = handles
            process = null
            handles = emptyList()
            generation = null
        }
        current?.let { backend.stop(it.pid); try { it.output?.close() } catch (_: Exception) { } }
        opened.asReversed().forEach { runCatching { it.close() } }
    }

    companion object {
        private const val MAX_DIAGNOSTIC_BYTES = 64 * 1024
    }

    private class EpochChangedException : IOException()
}

internal class UserlandProductSupervisor(
    private val product: UserlandProduct,
    private val runtimeRoot: File,
    private val devices: () -> List<UserlandUsbIdentity>,
    private val backend: UserlandOwnerBackend,
    private val onFailure: () -> Unit = {},
    private val onReady: (UserlandGeneration) -> Unit = {}
) {
    private val lock = Any()
    private val owners = linkedMapOf<String, UserlandEnclosureOwner>()
    @Volatile private var readyGenerations: List<UserlandGeneration> = emptyList()

    fun startReady(): List<UserlandGeneration> {
        val selected = when (product) {
            UserlandProduct.PIX -> UserlandEnclosureSelector.pix(devices())
            UserlandProduct.ASICEN -> UserlandEnclosureSelector.asicen(devices())
        }.associateBy { it.token }
        val current = synchronized(lock) {
            val stale = owners.filterKeys { it !in selected }
            stale.values.forEach(UserlandEnclosureOwner::stop)
            stale.keys.forEach(owners::remove)
            selected.forEach { (token, enclosure) ->
                if (owners[token]?.isRetired() == true) owners.remove(token)?.stop()
                owners.getOrPut(token) {
                    UserlandEnclosureOwner(
                        product, enclosure,
                        UserlandRuntimeLayout.runtimeDirectoryForEnclosure(runtimeRoot, token), backend, onFailure
                    )
                }
            }
            owners.values.toList()
        }
        return current.mapNotNull(UserlandEnclosureOwner::startOrGet).also {
            readyGenerations = it
            it.forEach(onReady)
        }
    }

    fun invalidateForDetach(deviceName: String) {
        synchronized(lock) {
            val affected = owners.filterValues { it.owns(deviceName) }.keys
            affected.forEach { token -> owners[token]?.invalidateForDetach(deviceName) }
            readyGenerations = readyGenerations.filterNot { it.instanceToken in affected }
        }
    }

    fun status(): String = synchronized(lock) { "${product.label}: ${readyGenerations.size} ready / ${owners.size} owner(s)" }

    fun generations(): List<UserlandGeneration> = readyGenerations

    fun stop() {
        synchronized(lock) {
            owners.values.forEach(UserlandEnclosureOwner::stop)
            owners.clear()
            readyGenerations = emptyList()
        }
    }
}
