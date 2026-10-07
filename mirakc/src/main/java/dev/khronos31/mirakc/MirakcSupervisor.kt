package dev.khronos31.mirakc

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal const val MIRAKC_JOB_FILTER_ARGS =
    "{{#sids}} --sids={{{.}}}{{/sids}}{{#xsids}} --xsids={{{.}}}{{/xsids}}"

internal enum class MirakcObservedPhase {
    STOPPED, STARTING_SCAN, SCANNING, SCAN_READY, STARTING_PUBLIC, RUNNING_PUBLIC,
    RESTARTING, FINISHING_SCAN, STOPPING, ERROR
}

internal data class MirakcSupervisorSnapshot(
    val phase: MirakcObservedPhase,
    val mode: MirakcRuntimeMode,
    val detail: String,
    val scan: GrScanStatus?
)

/** Owns the upstream mirakc process, but not tuner or smart-card descriptors. */
internal class MirakcSupervisor(
    private val context: Context,
    private val tunerDevices: () -> List<String>,
    private val openTuner: (Int, String) -> SianoUsbHandle,
    private val openReader: () -> SianoReaderHandle?,
    private val terrestrialChannels: () -> List<TerrestrialChannel>,
    private val firmware: () -> File,
    private val px4Devices: () -> List<Px4DeviceIdentity>,
    private val openPx4: (Px4DeviceIdentity) -> Px4UsbHandle,
    private val px4Firmware: () -> File,
    private val onStateChanged: () -> Unit,
    private val onStartupResult: (Boolean) -> Boolean,
    private val onGrScanStatus: (GrScanStatus) -> Unit = {}
) {
    private val lock = java.lang.Object()
    private val ownerGate = RuntimeOwnerGate()
    private val runtimeDir = File(context.filesDir, "mirakc-runtime")
    private val epgDir = File(context.filesDir, "epg")
    private var process: NativeUsbProcess.StartedMirakc? = null
    private var diagnosticReader: DiagnosticReader? = null
    private var startup: Thread? = null
    private var monitor: Thread? = null
    private var restart: Thread? = null
    private var crashRestart: Thread? = null
    private var crashRestartCount = 0
    private var reconfigurePending = false
    private var reconfigureHandoff = false
    private var stopping = false
    private var runtimeMode = MirakcRuntimeMode.PUBLIC
    private val ownerWorkers = linkedSetOf<Thread>()
    private var pendingScanCompletion: Pair<NativeUsbProcess.StartedMirakc, GrScanStatus>? = null
    @Volatile private var state = "stopped"
    private var observedPhase = MirakcObservedPhase.STOPPED
    private val grScanRequests = GrScanRequestLifecycle()
    private var registeredScanId: Long? = null
    private var registeringScanId: Long? = null
    private data class RuntimeAdapters(val broker: SianoTunerBroker, val px4: Px4DaemonSupervisor)
    private val runtimeAdapters = RuntimeResourceCycle(createRuntimeAdapters(), ::createRuntimeAdapters)
    private val broker: SianoTunerBroker get() = runtimeAdapters.current().broker
    private val px4: Px4DaemonSupervisor get() = runtimeAdapters.current().px4

    private fun createRuntimeAdapters(): RuntimeAdapters {
        lateinit var created: RuntimeAdapters
        val broker = SianoTunerBroker(
            sianoExecutable = { File(context.applicationInfo.nativeLibraryDir, "libsiano-ts.so") },
            firmware = firmware,
            tunerDevices = tunerDevices,
            openTuner = openTuner,
            openReader = openReader
        )
        val px4 = Px4DaemonSupervisor(
            executable = { File(context.applicationInfo.nativeLibraryDir, "libpx4d.so") },
            firmware = px4Firmware,
            identities = px4Devices,
            openDevice = openPx4,
            runtimeDir = File(context.filesDir, "p4"),
            onStateChanged = onStateChanged,
            onDaemonFailure = {
                synchronized(lock) {
                    if (runtimeAdapters.current() === created) {
                        Log.e(TAG, "PX4 daemon failed; scheduling mirakc reconfigure")
                        reconfigure()
                    }
                }
            }
        )
        created = RuntimeAdapters(broker, px4)
        return created
    }

    /** Caller holds lock; resources are renewed only when the prior owner fully retired. */
    private fun renewRuntimeAdaptersIfTerminalLocked() {
        runtimeAdapters.replaceAfterQuiescence(
            quiescent = !stopping && process == null && ownerWorkers.isEmpty() &&
                ownerGate.mayStart() && ownerGate.isQuiescent()
        )
    }

    private fun closeRuntimeAdapters() {
        val adapters = runtimeAdapters.current()
        adapters.broker.close()
        adapters.px4.stop()
        runtimeAdapters.markTerminallyClosed(adapters)
    }

    fun start(): Boolean = start(MirakcRuntimeMode.PUBLIC)

    fun startScanOnly(): Boolean = start(MirakcRuntimeMode.SCAN_ONLY)

    private fun start(mode: MirakcRuntimeMode): Boolean {
        return synchronized(lock) {
            if (stopping || !ownerGate.mayStart()) return@synchronized false
            if (process != null || ownerWorkers.isNotEmpty() || startup?.isAlive == true ||
                monitor?.isAlive == true || restart?.isAlive == true || crashRestart?.isAlive == true
            ) return@synchronized runtimeMode == mode && process != null
            renewRuntimeAdaptersIfTerminalLocked()
            stopping = false
            runtimeMode = mode
            crashRestartCount = 0
            setStateLocked(
                if (mode == MirakcRuntimeMode.SCAN_ONLY) "starting scan runtime" else "starting public server",
                if (mode == MirakcRuntimeMode.SCAN_ONLY) MirakcObservedPhase.STARTING_SCAN
                else MirakcObservedPhase.STARTING_PUBLIC
            )
            startup = launchOwnerWorkerLocked("mirakc-supervisor-start-${mode.name.lowercase()}") {
                startOnWorker(mode)
            }
            true
        }
    }

    fun stop() {
        val current: NativeUsbProcess.StartedMirakc?
        val workers: List<Thread>
        synchronized(lock) {
            if (stopping) return
            stopping = true
            ownerGate.beginStop()
            setStateLocked("stopping", MirakcObservedPhase.STOPPING)
            workers = ownerWorkers.toList()
            workers.forEach(Thread::interrupt)
            // Invalidate an in-flight USB reconfiguration before closing the
            // broker. Its worker must never reopen the abstract socket after
            // shutdown has completed.
            reconfigurePending = false
            reconfigureHandoff = false
            markGrScanInterruptedIfRunning()
            grScanRequests.clear()
            current = process
        }
        // nativeStop terminates the complete process group.  Do this outside
        // the lock so a state callback cannot ever wait on process teardown.
        current?.let {
            stopStarted(it, "service-stop")
            synchronized(lock) { if (process?.pid == it.pid) process = null }
        }
        closeRuntimeAdapters()
        workers.filter { it !== Thread.currentThread() }.forEach { worker ->
            try {
                worker.join(STOP_WORKER_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val stillActive = workers.filter { it !== Thread.currentThread() && it.isAlive }
        if (stillActive.isNotEmpty()) {
            Thread({
                stillActive.forEach { worker ->
                    try {
                        worker.join()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
                finishStop()
            }, "mirakc-supervisor-stop-barrier").also {
                it.isDaemon = true
                it.start()
            }
            return
        }
        finishStop()
    }

    /**
     * Wait until process and worker ownership has really drained, including a
     * deferred stop barrier. Call only from a background teardown worker.
     */
    fun awaitQuiescent(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(lock) {
            while (process != null || ownerWorkers.isNotEmpty() || stopping || !ownerGate.mayStart()) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0L) return false
                val millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
                lock.wait(millis)
            }
            return true
        }
    }

    private fun finishStop() {
        val lateProcess = synchronized(lock) { process }
        lateProcess?.let { stopStarted(it, "stop-barrier") }
        closeRuntimeAdapters()
        synchronized(lock) {
            process = null
            startup = null
            monitor = null
            restart = null
            crashRestart = null
            try {
                clearUpdateSchedulesTrigger()
                clearGrScanTriggers()
            } catch (error: IOException) {
                Log.e(TAG, "acceptance trigger cleanup failed during stop", error)
            }
            setStateLocked("stopped", MirakcObservedPhase.STOPPED)
            stopping = false
            ownerGate.finishStop()
            pendingScanCompletion = null
            lock.notifyAll()
        }
    }

    fun reconfigure() {
        synchronized(lock) {
            if (stopping) return
            if (restart?.isAlive == true) {
                // Coalesce attach/detach events while the current restart is
                // tearing down or probing. The restart worker consumes this
                // flag after it clears its own marker.
                reconfigurePending = true
                return
            }
            if (crashRestart?.isAlive == true) {
                reconfigurePending = true
                return
            }
            if (startup?.isAlive == true) {
                // Permission may be granted while the initial probe is still
                // running. Re-read UsbManager state once that start reaches
                // running instead of losing this lifecycle event.
                reconfigurePending = true
                return
            }
            if (process == null) return
            setStateLocked("restarting for USB change", MirakcObservedPhase.RESTARTING)
            Log.i(TAG, "breadcrumb reconfigure scheduled")
            restart = launchOwnerWorkerLocked("mirakc-supervisor-restart") { restartOnWorker() }
        }
    }

    /** Restart (or start) after an explicit configuration transaction. */
    fun restartForConfiguration() {
        synchronized(lock) {
            if (stopping) return
            if (process == null) {
                if (startup?.isAlive != true) {
                    start(MirakcRuntimeMode.PUBLIC)
                } else {
                    reconfigurePending = true
                }
                return
            }
            if (restart?.isAlive == true || startup?.isAlive == true) {
                reconfigurePending = true
                return
            }
            setStateLocked("restarting for terrestrial configuration", MirakcObservedPhase.RESTARTING)
            restart = launchOwnerWorkerLocked("mirakc-supervisor-config-restart") { restartOnWorker() }
        }
    }

    fun status(): String = state

    fun snapshot(): MirakcSupervisorSnapshot = synchronized(lock) {
        MirakcSupervisorSnapshot(observedPhase, runtimeMode, state, grScanStatus())
    }

    fun px4Status(): String = px4.status()

    /** Invalidate a PX4 owner's descriptors directly from Android's detach event. */
    fun invalidatePx4Device(deviceName: String) {
        px4.invalidateDetachedDevice(deviceName)
    }

    /** Ask the running upstream JobManager to invoke its real EIT job once. */
    fun triggerUpdateSchedules(): Boolean {
        synchronized(lock) {
            val triggerParent = updateSchedulesTrigger.parentFile ?: return false
            if (stopping || process == null || !triggerParent.isDirectory) return false
            if (updateSchedulesTrigger.exists()) return false
            return try {
                updateSchedulesTrigger.createNewFile()
            } catch (error: IOException) {
                Log.e(TAG, "update-schedules trigger failed", error)
                false
            }
        }
    }

    /** Queue the range scan; upstream JobManager takes the low-priority tuner lease. */
    fun requestGrScan(scanId: Long = System.currentTimeMillis().coerceAtLeast(1L)): Boolean {
        return synchronized(lock) {
            val parent = grScanTrigger.parentFile ?: return false
            if (stopping || runtimeMode != MirakcRuntimeMode.SCAN_ONLY) return false
            val startupActive = startup?.isAlive == true || restart?.isAlive == true ||
                crashRestart?.isAlive == true || reconfigurePending || reconfigureHandoff
            val processReady = process != null && parent.isDirectory && !startupActive
            val existingStatus = grScanStatus()?.takeIf { processReady }
            when (val decision = grScanRequests.request(
                processReady, startupActive, existingStatus, requestedScanId = scanId
            )) {
                GrScanRequestLifecycle.Decision.Busy,
                GrScanRequestLifecycle.Decision.Unavailable -> return false
                is GrScanRequestLifecycle.Decision.WaitForStartup -> true
                is GrScanRequestLifecycle.Decision.Execute -> {
                    if (queueGrScanLocked(decision.status)) {
                        grScanRequests.activated(decision.status.scanId)
                        true
                    } else {
                        grScanRequests.triggerFailed(decision.status.scanId)
                        false
                    }
                }
            }
        }
    }

    /** Cancellation takes effect between channels, after the current tuner lease is released. */
    fun cancelGrScan(): Boolean {
        var cancellationAccepted = false
        var queuedCancellation: GrScanStatus? = null
        synchronized(lock) {
            val parent = grScanCancelTrigger.parentFile ?: return false
            if (stopping) return false
            if (grScanRequests.cancelPending()) {
                cancellationAccepted = true
                queuedCancellation = grScanStatus()
            }
            if (cancellationAccepted) {
                markGrScanInterruptedIfRunning()
            } else {
            if (process == null || runtimeMode != MirakcRuntimeMode.SCAN_ONLY ||
                !parent.isDirectory || grScanStatus()?.isRunning != true
            ) return false
            cancellationAccepted = true
            try {
                if (!grScanCancelTrigger.exists()) grScanCancelTrigger.createNewFile()
            } catch (error: IOException) {
                Log.e(TAG, "GR scan cancellation trigger failed", error)
                return false
            }
            }
        }
        // Killing the isolated scan process group releases a potentially
        // blocked tuner lease promptly. No public service work shares it.
        stop()
        queuedCancellation?.let { status ->
            try {
                val parent = grScanStateFile.parentFile ?: throw IOException("scan state has no parent directory")
                mkdir(parent)
                writeGrScanState(status.toFileContents())
            } catch (error: IOException) {
                Log.e(TAG, "unable to persist queued scan cancellation", error)
            }
        }
        return true
    }

    fun grScanStatus(): GrScanStatus? {
        synchronized(lock) {
            val persisted = if (!grScanStateFile.isFile || grScanStateFile.length() > MAX_SCAN_STATUS_BYTES) {
                null
            } else {
                try {
                    GrScanStatus.parse(grScanStateFile.readText(StandardCharsets.UTF_8))
                } catch (_: IOException) {
                    null
                }
            }
            return grScanRequests.visibleStatus(persisted)
        }
    }

    /** Write the queued state before making the JobManager trigger visible. */
    private fun queueGrScanLocked(status: GrScanStatus): Boolean {
        if (stopping || process == null || grScanTrigger.parentFile?.isDirectory != true || grScanTrigger.exists()) {
            return false
        }
        return try {
            val previousState = grScanStateFile.takeIf { it.isFile && it.length() <= MAX_SCAN_STATUS_BYTES }
                ?.readText(StandardCharsets.UTF_8)
            if (grScanCancelTrigger.exists() && !grScanCancelTrigger.delete()) {
                throw IOException("cannot clear stale GR cancellation marker")
            }
            writeGrScanState(status.toFileContents())
            if (!grScanTrigger.createNewFile()) {
                previousState?.let { writeGrScanState(it) }
                false
            } else {
                true
            }
        } catch (error: IOException) {
            Log.e(TAG, "GR scan trigger failed", error)
            false
        }
    }

    private fun startOnWorker(mode: MirakcRuntimeMode = MirakcRuntimeMode.PUBLIC) {
        var started: NativeUsbProcess.StartedMirakc? = null
        try {
            synchronized(lock) {
                if (stopping) return
            }
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val executable = nativeDir.resolve("libmirakc.so")
            if (!executable.isFile) throw IOException("upstream mirakc is not packaged: $executable")

            // Public and discovery runtimes have isolated cache/config/listener
            // ownership. Both use the same broker and PX4 device adapters.
            val layout = mirakcRuntimeLayout(context.filesDir, mode)
            mkdir(layout.runtimeDirectory)
            mkdir(layout.cacheDirectory)
            mkdir(layout.recordingDirectory)
            // A marker is meaningful only while this exact upstream process
            // is alive; never carry one across a crash/restart.
            if (mode == MirakcRuntimeMode.SCAN_ONLY) {
                clearUpdateSchedulesTrigger()
                clearGrScanTriggers()
                markGrScanInterruptedIfRunning()
            }
            synchronized(lock) {
                if (stopping) return
            }
            val generation = broker.start()
            val px4Generation = px4.startAllOrGet()
            val abortAfterBrokerStart = synchronized(lock) { stopping }
            if (abortAfterBrokerStart) {
                // stop() may have raced with broker.start(); do not leave a
                // newly-created abstract socket behind after shutdown.
                closeRuntimeAdapters()
                return
            }
            val strings = layout.runtimeDirectory.resolve("strings.yml")
            context.assets.open("mirakc-strings.yml").use { input ->
                strings.outputStream().use { output -> input.copyTo(output) }
            }
            val config = layout.runtimeDirectory.resolve("config.yml")
            val temporaryConfig = config.resolveSibling("config.yml.tmp")
            temporaryConfig.writeText(
                buildConfig(layout, strings, generation, px4Generation, mode),
                StandardCharsets.UTF_8
            )
            if (!temporaryConfig.renameTo(config)) {
                throw IOException("cannot atomically install mirakc config: $config")
            }

            val launched = NativeUsbProcess.startMirakc(executable.absolutePath, config.absolutePath)
            started = launched
            Log.i(TAG, "breadcrumb upstream start pid=${launched.pid}")
            startDiagnostics(launched)
            val abortStartup = synchronized(lock) {
                if (stopping) {
                    true
                } else {
                    process = launched
                    setStateLocked(
                        "probing (pid ${launched.pid})",
                        if (mode == MirakcRuntimeMode.SCAN_ONLY) MirakcObservedPhase.STARTING_SCAN
                        else MirakcObservedPhase.STARTING_PUBLIC
                    )
                    false
                }
            }
            if (abortStartup) {
                stopStarted(launched, "stop-during-startup")
                return
            }
            probeVersion(launched.pid, layout.listenAddress.substringAfterLast(':').toInt())
            when (val result = NativeUsbProcess.pollMirakc(launched.pid)) {
                NativeUsbProcess.PollResult.ALIVE -> Unit
                is NativeUsbProcess.PollResult.EXITED ->
                    throw IOException("mirakc exited after /api/version probe (${describe(result)})")
                NativeUsbProcess.PollResult.ERROR ->
                    throw IOException("unable to poll mirakc after /api/version probe")
            }
            Log.i(TAG, "breadcrumb probe success pid=${launched.pid}")
            val (rerun, deferScanForReconfigure) = synchronized(lock) {
                if (process?.pid != launched.pid || stopping) return
                // Read and clear this while startup is still marked alive so
                // a permission event cannot be lost between those operations.
                val restartActive = restart?.isAlive == true
                val pending = reconfigurePending
                if (!restartActive) reconfigurePending = false
                val rerun = pending && !restartActive
                if (rerun) reconfigureHandoff = true
                startup = null
                setStateLocked(
                    if (mode == MirakcRuntimeMode.SCAN_ONLY) "scan runtime ready (pid ${launched.pid})"
                    else "running (pid ${launched.pid})",
                    if (mode == MirakcRuntimeMode.SCAN_ONLY) MirakcObservedPhase.SCAN_READY
                    else MirakcObservedPhase.RUNNING_PUBLIC
                )
                monitor = launchOwnerWorkerLocked("mirakc-supervisor-monitor") { monitor(launched, mode) }
                // A restart worker leaves the flag for its finally block,
                // which clears its marker before scheduling the follow-up.
                rerun to pending
            }
            activatePendingGrScan(deferForReconfigure = deferScanForReconfigure)
            if (!rerun && mode == MirakcRuntimeMode.PUBLIC) onStartupResult(true)
            // A restart worker cannot recursively schedule itself while its
            // marker is alive; its finally block handles that coalesced event.
            if (rerun) {
                if (synchronized(lock) { restart?.isAlive != true }) reconfigure()
                synchronized(lock) { reconfigureHandoff = false }
            }
        } catch (error: Exception) {
            Log.e(TAG, "breadcrumb startup failure")
            started?.let { stopStarted(it, "startup-failure") }
            // A failed startup must not leave an acceptance trigger for a
            // later, unrelated upstream process.
            try {
                clearUpdateSchedulesTrigger()
            } catch (cleanupError: IOException) {
                Log.e(TAG, "acceptance trigger cleanup failed after startup error", cleanupError)
            }
            val scanStartupFailure = synchronized(lock) {
                if (stopping) {
                    startup = null
                    return
                }
                val scanFailure = grScanRequests.startupFailed()
                if (process?.pid == started?.pid) process = null
                startup = null
                setStateLocked("error (startup): ${error.message ?: error.javaClass.simpleName}", MirakcObservedPhase.ERROR)
                scanFailure
            }
            scanStartupFailure?.let(onGrScanStatus)
            val rollbackHandled = onStartupResult(false)
            if (!rollbackHandled && mode == MirakcRuntimeMode.PUBLIC) {
                synchronized(lock) {
                    if (!stopping && crashRestart == null) launchRetryLocked()
                }
            }
        }
    }

    private fun restartOnWorker() {
        synchronized(lock) {
            if (stopping) {
                restart = null
                return
            }
        }
        val current: NativeUsbProcess.StartedMirakc?
        synchronized(lock) {
            current = process
            process = null
            monitor?.interrupt()
            monitor = null
        }
        current?.let { stopStarted(it, "usb-reconfigure") }
        try {
            broker.rotateGeneration()
            px4.reconfigure()
            synchronized(lock) {
                if (stopping) return
            }
            startOnWorker(runtimeMode)
        } finally {
            val rerun = synchronized(lock) {
                val pending = reconfigurePending
                reconfigurePending = false
                reconfigureHandoff = pending
                restart = null
                pending
            }
            if (rerun) {
                reconfigure()
                synchronized(lock) { reconfigureHandoff = false }
            }
        }
    }

    private fun clearUpdateSchedulesTrigger() {
        if (updateSchedulesTrigger.exists() && !updateSchedulesTrigger.delete()) {
            throw IOException("cannot remove stale acceptance trigger: $updateSchedulesTrigger")
        }
    }

    private fun clearGrScanTriggers() {
        listOf(grScanTrigger, grScanCancelTrigger).forEach { marker ->
            if (marker.exists() && !marker.delete()) {
                throw IOException("cannot remove stale GR scan trigger: $marker")
            }
        }
    }

    /** Once mirakc is gone, preserve progress while making an abandoned scan non-applicable. */
    private fun markGrScanInterruptedIfRunning() {
        val status = grScanStatus()?.takeIf { it.isRunning } ?: return
        try {
            writeGrScanState(status.asInterrupted("INTERRUPTED").toFileContents())
        } catch (error: IOException) {
            Log.e(TAG, "unable to mark interrupted GR scan", error)
        }
    }

    private fun writeGrScanState(contents: String) {
        val temporary = grScanStateFile.resolveSibling(".gr-scan-state.android.tmp")
        temporary.writeText(contents, StandardCharsets.UTF_8)
        if (!temporary.renameTo(grScanStateFile)) {
            throw IOException("cannot replace GR scan status: $grScanStateFile")
        }
    }

    /** Convert a scan requested while startup was probing into the real JobManager trigger. */
    private fun activatePendingGrScan(deferForReconfigure: Boolean) {
        synchronized(lock) {
            grScanRequests.activateWhenReady(deferForReconfigure) { pending -> queueGrScanLocked(pending) }
        }
    }

    /** Drain requests accepted during worker retirement after this PID becomes the stable generation. */
    private fun activatePendingGrScanForStableProcess(started: NativeUsbProcess.StartedMirakc) {
        synchronized(lock) {
            if (stopping || process?.pid != started.pid || startup?.isAlive == true ||
                restart?.isAlive == true || crashRestart?.isAlive == true ||
                reconfigurePending || reconfigureHandoff
            ) {
                return
            }
            grScanRequests.activateWhenReady(deferForReconfigure = false) { pending -> queueGrScanLocked(pending) }
        }
    }

    /** Apply a successful scan to persistent pending settings even when no TV Activity is visible. */
    private fun observeCompletedGrScan(started: NativeUsbProcess.StartedMirakc) {
        val completed = grScanStatus()?.takeIf {
            it.state == GrScanStatus.State.EMPTY || it.isApplicable
        } ?: return
        val shouldRegister = synchronized(lock) {
            if (process?.pid != started.pid || registeredScanId == completed.scanId ||
                registeringScanId == completed.scanId
            ) {
                false
            } else {
                registeringScanId = completed.scanId
                true
            }
        }
        if (!shouldRegister) {
            return
        }
        try {
            onGrScanStatus(completed)
            synchronized(lock) { registeredScanId = completed.scanId }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to register completed GR scan", error)
        } finally {
            synchronized(lock) { if (registeringScanId == completed.scanId) registeringScanId = null }
        }
    }

    private fun probeVersion(pid: Int, port: Int) {
        val deadline = System.nanoTime() + STARTUP_TIMEOUT_NS
        var lastError = "no response"
        while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
            when (val result = NativeUsbProcess.pollMirakc(pid)) {
                NativeUsbProcess.PollResult.ALIVE -> Unit
                is NativeUsbProcess.PollResult.EXITED ->
                    throw IOException("mirakc exited during startup probe (${describe(result)})")
                NativeUsbProcess.PollResult.ERROR -> throw IOException("unable to poll mirakc during startup probe")
            }
            try {
                val connection = URL("http://127.0.0.1:$port/api/version").openConnection() as HttpURLConnection
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.requestMethod = "GET"
                try {
                    val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    if (connection.responseCode == HttpURLConnection.HTTP_OK &&
                        body.contains("\"current\":\"3.4.88\"")) return
                    lastError = "unexpected version response"
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
            }
            try {
                Thread.sleep(PROBE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                throw IOException("startup interrupted")
            }
        }
        throw IOException("mirakc /api/version probe timed out ($lastError)")
    }

    private fun monitor(started: NativeUsbProcess.StartedMirakc, mode: MirakcRuntimeMode) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
            when (val result = NativeUsbProcess.pollMirakc(started.pid)) {
                NativeUsbProcess.PollResult.ALIVE -> {
                    if (mode == MirakcRuntimeMode.SCAN_ONLY) {
                        val scanStatus = grScanStatus()
                        if (scanStatus != null && !scanStatus.isRunning) {
                            stopCompletedScanRuntime(started, scanStatus)
                            return
                        }
                    }
                    activatePendingGrScanForStableProcess(started)
                    observeCompletedGrScan(started)
                    continue
                }
                is NativeUsbProcess.PollResult.EXITED,
                NativeUsbProcess.PollResult.ERROR -> {
                    // pollMirakc reaps an exited child; do not call
                    // nativeStop on a reaped/reused PID.
                    if (result is NativeUsbProcess.PollResult.EXITED) {
                        Log.e(TAG, "breadcrumb monitor EXITED pid=${started.pid} ${describe(result)}")
                    } else {
                        Log.e(TAG, "breadcrumb monitor ERROR pid=${started.pid}")
                    }
                    synchronized(lock) {
                        if (process?.pid != started.pid) return
                        process = null
                        monitor = null
                        if (mode == MirakcRuntimeMode.SCAN_ONLY) {
                            markGrScanInterruptedIfRunning()
                            setStateLocked("scan runtime stopped unexpectedly", MirakcObservedPhase.ERROR)
                        } else {
                            setStateLocked(
                                if (result is NativeUsbProcess.PollResult.EXITED) {
                                    "crashed (pid ${started.pid}, ${describe(result)})"
                                } else {
                                    "crashed (pid ${started.pid}, poll error)"
                                }, MirakcObservedPhase.ERROR
                            )
                            launchRetryLocked()
                        }
                    }
                    finishDiagnostics(started)
                    if (mode == MirakcRuntimeMode.SCAN_ONLY) {
                        closeRuntimeAdapters()
                        grScanStatus()?.takeIf { it.state == GrScanStatus.State.INTERRUPTED }
                            ?.let(onGrScanStatus)
                    }
                    return
                }
            }
        }
    }

    private fun crashRestartOnWorker(attempt: Int) {
        val worker = Thread.currentThread()
        try {
            Thread.sleep(CRASH_RESTART_BACKOFF_MS shl attempt)
            synchronized(lock) {
                if (stopping || process != null) return
                startup = worker
                setStateLocked(
                    "restarting after crash (attempt ${attempt + 1}/$MAX_CRASH_RESTARTS)",
                    MirakcObservedPhase.STARTING_PUBLIC
                )
            }
            broker.rotateGeneration()
            synchronized(lock) {
                if (stopping) return
            }
            startOnWorker(runtimeMode)
        } catch (_: InterruptedException) {
            // stop() interrupts the bounded backoff during service teardown.
        } catch (error: Exception) {
            synchronized(lock) {
                if (!stopping) setStateLocked(
                    "error (crash restart): ${error.message ?: error.javaClass.simpleName}",
                    MirakcObservedPhase.ERROR
                )
            }
        } finally {
            val rerun = synchronized(lock) {
                if (startup === worker) startup = null
                val wasRetryWorker = crashRestart === worker
                if (wasRetryWorker) crashRestart = null
                if (wasRetryWorker && process == null) launchRetryLocked()
                val pending = reconfigurePending
                reconfigurePending = false
                reconfigureHandoff = pending
                pending
            }
            if (rerun) {
                reconfigure()
                synchronized(lock) { reconfigureHandoff = false }
            }
        }
    }

    private fun launchRetryLocked() {
        if (stopping || crashRestart != null || crashRestartCount >= MAX_CRASH_RESTARTS) return
        val attempt = crashRestartCount++
        crashRestart = launchOwnerWorkerLocked("mirakc-supervisor-crash-restart") {
            crashRestartOnWorker(attempt)
        }
    }

    private fun setStateLocked(value: String, phase: MirakcObservedPhase = observedPhase) {
        state = value
        observedPhase = phase
        // The callback only rebuilds the service status text and never takes
        // this supervisor's lock, so state notifications cannot deadlock.
        onStateChanged()
    }

    /** Register before starting so stop() can join even after a worker clears its role field. */
    private fun launchOwnerWorkerLocked(name: String, work: () -> Unit): Thread {
        check(ownerGate.ownerStarted()) { "runtime owner is stopping" }
        val worker = Thread({
            try {
                work()
            } finally {
                val finishScan = synchronized(lock) {
                    ownerWorkers.remove(Thread.currentThread())
                    ownerGate.ownerFinished()
                    pendingScanCompletion.takeIf { stopping && ownerWorkers.isEmpty() }?.also {
                        pendingScanCompletion = null
                    }
                }
                finishScan?.let { finishCompletedScan(it.first, it.second) }
            }
        }, name).also { it.isDaemon = true }
        ownerWorkers += worker
        worker.start()
        return worker
    }

    private data class DiagnosticReader(
        val process: NativeUsbProcess.StartedMirakc,
        val thread: Thread
    )

    /** Drain upstream stdout/stderr without allowing an unbounded log pipe. */
    private fun startDiagnostics(started: NativeUsbProcess.StartedMirakc) {
        lateinit var handle: DiagnosticReader
        val reader = Thread({
            try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(started.output).use { input ->
                    val buffer = ByteArray(1024)
                    val line = StringBuilder()
                    var emitted = 0
                    // Continue draining after the log budget is exhausted;
                    // closing the read end here would turn a noisy upstream
                    // into SIGPIPE and obscure the actual server failure.
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (emitted >= MAX_DIAGNOSTICS_BYTES) continue
                        for (index in 0 until count) {
                            val value = buffer[index].toInt() and 0xff
                            if (value == '\n'.code) {
                                val remaining = MAX_DIAGNOSTICS_BYTES - emitted
                                if (remaining > 0) {
                                    val diagnostic = line.toString().take(remaining)
                                    logDiagnostic(started.pid, diagnostic)
                                    emitted += diagnostic.length
                                }
                                line.setLength(0)
                            } else if (line.length < MAX_DIAGNOSTIC_LINE) {
                                line.append(if (value in 0x20..0x7e || value >= 0xa0) value.toChar() else '?')
                            }
                        }
                    }
                    if (line.isNotEmpty() && emitted < MAX_DIAGNOSTICS_BYTES) {
                        logDiagnostic(started.pid, line.toString().take(MAX_DIAGNOSTICS_BYTES - emitted))
                    }
                }
            } catch (_: IOException) {
                // Closing the descriptor during normal stop is expected.
            } finally {
                synchronized(lock) {
                    if (diagnosticReader === handle) diagnosticReader = null
                }
            }
        }, "mirakc-diagnostics-${started.pid}").also {
            it.isDaemon = true
        }
        handle = DiagnosticReader(started, reader)
        synchronized(lock) { diagnosticReader = handle }
        reader.start()
    }

    private fun logDiagnostic(pid: Int, line: String) {
        if (line.isNotBlank()) Log.e(TAG, "upstream[$pid] ${line.take(MAX_DIAGNOSTIC_LINE)}")
    }

    private fun finishDiagnostics(started: NativeUsbProcess.StartedMirakc) {
        val reader = synchronized(lock) {
            val current = diagnosticReader
            if (current?.process === started) current else null
        }
        if (reader != null && reader.thread !== Thread.currentThread()) {
            try {
                reader.thread.join(DIAGNOSTICS_DRAIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // The writer is already gone after nativeStop/poll. Closing only this
        // process' descriptor cannot affect a newer generation's reader.
        try { started.output.close() } catch (_: IOException) { }
        if (reader != null && reader.thread.isAlive && reader.thread !== Thread.currentThread()) {
            reader.thread.interrupt()
        }
        synchronized(lock) {
            if (diagnosticReader === reader) diagnosticReader = null
        }
    }

    private fun stopStarted(started: NativeUsbProcess.StartedMirakc, reason: String) {
        Log.i(TAG, "breadcrumb stopStarted reason=$reason pid=${started.pid}")
        try {
            NativeUsbProcess.stop(started.pid)
        } finally {
            finishDiagnostics(started)
            markGrScanInterruptedIfRunning()
        }
    }

    private fun stopCompletedScanRuntime(
        started: NativeUsbProcess.StartedMirakc,
        status: GrScanStatus
    ) {
        val otherWorkers: List<Thread>
        synchronized(lock) {
            if (process?.pid != started.pid || stopping) return
            stopping = true
            ownerGate.beginStop()
            setStateLocked("finishing channel scan", MirakcObservedPhase.FINISHING_SCAN)
            otherWorkers = ownerWorkers.filter { it !== Thread.currentThread() }
            otherWorkers.forEach(Thread::interrupt)
        }
        stopStarted(started, "scan-${status.state.name.lowercase()}")
        closeRuntimeAdapters()
        otherWorkers.forEach { worker ->
            try {
                worker.join(STOP_WORKER_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        val slowWorkers = otherWorkers.filter(Thread::isAlive)
        if (slowWorkers.isNotEmpty()) {
            Thread({
                slowWorkers.forEach { it.join() }
                closeRuntimeAdapters()
                completeScanStop(started, status)
            }, "mirakc-scan-stop-barrier").also {
                it.isDaemon = true
                it.start()
            }
            return
        }
        closeRuntimeAdapters()
        completeScanStop(started, status)
    }

    private fun completeScanStop(started: NativeUsbProcess.StartedMirakc, status: GrScanStatus) {
        synchronized(lock) {
            pendingScanCompletion = started to status
        }
        finishPendingScanIfQuiescent()
    }

    private fun finishPendingScanIfQuiescent() {
        val pending = synchronized(lock) {
            if (!stopping || ownerWorkers.isNotEmpty()) return
            pendingScanCompletion.also { pendingScanCompletion = null }
        } ?: return
        finishCompletedScan(pending.first, pending.second)
    }

    private fun finishCompletedScan(started: NativeUsbProcess.StartedMirakc, status: GrScanStatus) {
        try {
            onGrScanStatus(status)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to persist completed GR scan", error)
        }
        synchronized(lock) {
            if (process?.pid != started.pid) {
                stopping = false
                return
            }
            process = null
            monitor = null
            startup = null
            try {
                clearGrScanTriggers()
            } catch (error: IOException) {
                Log.e(TAG, "GR scan trigger cleanup failed after scan stop", error)
            }
            setStateLocked("scan ${status.state.name.lowercase()} (runtime stopped)", MirakcObservedPhase.STOPPED)
            stopping = false
            ownerGate.finishStop()
            lock.notifyAll()
        }
    }

    private fun describe(result: NativeUsbProcess.PollResult.EXITED): String = when {
        result.code != null -> "exit=${result.code}"
        result.signal != null -> "signal=${result.signal}"
        else -> "exit=unknown"
    }

    private fun mkdir(directory: File) {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("cannot create mirakc directory: $directory")
        }
    }

    private fun buildConfig(
        layout: MirakcRuntimeLayout,
        strings: File,
        generation: SianoGeneration,
        px4Generations: List<Px4Generation>,
        mode: MirakcRuntimeMode
    ): String {
        val arib = File(context.applicationInfo.nativeLibraryDir, "libmirakc-arib.so")
        val adapter = File(context.applicationInfo.nativeLibraryDir, "libmirakc-siano-adapter.so")
        val b25Filter = File(context.applicationInfo.nativeLibraryDir, "libmirakc-b25-filter.so")
        val px4Adapter = File(context.applicationInfo.nativeLibraryDir, "libmirakc-px4-adapter.so")
        fun yamlPath(file: File): String = file.absolutePath.replace("'", "''")
        val aribPath = yamlPath(arib)
        val b25FilterPath = yamlPath(b25Filter)
        val tunerConfig = buildString {
            if (generation.tunerCount == 0 && px4Generations.isEmpty()) {
                append("tuners: []\n")
            } else {
                append("tuners:\n")
            }
            if (generation.tunerCount > 0) {
                repeat(generation.tunerCount) { index ->
                    append("  - name: Siano-$index\n")
                    append("    types: [GR]\n")
                    append("    command: ")
                    append(yamlPath(adapter))
                    append(" --socket=@")
                    append(generation.socketName)
                    append(" --token=")
                    append(generation.token)
                    append(" --tuner-index=")
                    append(index)
                    append(" --channel={{{channel}}}\n")
                }
            }
            px4Generations.forEach { px4 ->
                val px4Ts = yamlPath(File(context.applicationInfo.nativeLibraryDir, "libpx4-ts.so"))
                px4.tuners.forEach { tuner ->
                    val tunerTypes = buildList {
                        if (tuner.supportsTerrestrial) add("GR")
                        if (tuner.supportsSatellite) {
                            add("BS")
                            add("CS")
                        }
                    }
                    append("  - name: ${tuner.name}\n")
                    append("    types: [${tunerTypes.joinToString(", ")}]\n")
                    append("    decoded: true\n")
                    append("    command: ")
                    append(yamlPath(px4Adapter))
                    append(" --px4-ts=")
                    append(px4Ts)
                    append(" ")
                    append(px4.adapterArguments(tuner.receiver).joinToString(" "))
                    append(" --runtime-dir=")
                    append(yamlPath(px4.runtimeDir))
                    append(" --channel={{{channel}}}")
                    if (tuner.supportsSatellite) append(" {{{extra_args}}}")
                    append("\n")
                }
            }
        }
        val hasSatellitePx4 = px4Generations.any { px4 -> px4.tuners.any { it.supportsSatellite } }
        val satelliteChannelConfig = if (!hasSatellitePx4 || mode == MirakcRuntimeMode.SCAN_ONLY) {
            ""
        } else {
            renderPx4SatelliteChannelConfig()
        }
        val terrestrialChannelConfig = if (mode == MirakcRuntimeMode.SCAN_ONLY) {
            "channels: []\n"
        } else {
            renderTerrestrialChannelConfig(
                terrestrialChannels(),
                appendListHeaderWhenEmpty = px4Generations.any { px4 -> px4.tuners.any { it.supportsTerrestrial } }
            )
        }
        val epgIntervalMinutes = EpgUpdateIntervalStore(
            AndroidStringSettings(
                context.getSharedPreferences("epg-update-settings", Context.MODE_PRIVATE)
            )
        ).minutes()
        val jobsConfig = renderMirakcJobCommands(aribPath, epgIntervalMinutes, enabled = mode == MirakcRuntimeMode.PUBLIC)
        return """
            |epg:
            |  cache-dir: '${yamlPath(layout.cacheDirectory)}'
            |server:
            |  addrs:
            |    - http: '${layout.listenAddress}'
            |$terrestrialChannelConfig$satelliteChannelConfig$tunerConfig|filters:
            |  decode-filter:
            |    command: '$b25FilterPath --socket=@${generation.socketName} --token=${generation.token}'
            |  service-filter:
            |    command: $aribPath filter-service --sid={{{sid}}}
            |  program-filter:
            |    command: $aribPath filter-program --sid={{{sid}}} --eid={{{eid}}} --clock-pid={{{clock_pid}}} --clock-pcr={{{clock_pcr}}} --clock-time={{{clock_time}}} --end-margin=2000
            |jobs:
            |$jobsConfig
            |timeshift:
            |  command: $aribPath record-service --sid={{{sid}}} --file={{{file}}} --chunk-size={{{chunk_size}}} --num-chunks={{{num_chunks}}} --start-pos={{{start_pos}}}
            |resource:
            |  strings-yaml: '${yamlPath(strings)}'
            |recording:
            |  basedir: '${yamlPath(layout.recordingDirectory)}'
        """.trimMargin() + "\n"
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 250
        const val PROBE_INTERVAL_MS = 100L
        // Enabled upstream jobs run before the HTTP listener is ready. Allow
        // a cold cache and two tuners to complete while retaining a hard bound.
        const val STARTUP_TIMEOUT_NS = 900_000_000_000L
        const val MAX_CRASH_RESTARTS = 3
        const val CRASH_RESTART_BACKOFF_MS = 1_000L
        const val MAX_DIAGNOSTICS_BYTES = 64 * 1024
        const val MAX_DIAGNOSTIC_LINE = 512
        const val DIAGNOSTICS_DRAIN_TIMEOUT_MS = 500L
        const val STOP_WORKER_TIMEOUT_MS = 2_000L
        const val MAX_SCAN_STATUS_BYTES = 2 * 1024
        const val TAG = "MirakcSupervisor"
    }

    private val updateSchedulesTrigger: File
        get() = epgDir.resolve("cache/.acceptance-update-schedules")
    private val grScanTrigger: File
        get() = File(context.filesDir, "mirakc-scan-runtime/cache/.acceptance-gr-scan")
    private val grScanCancelTrigger: File
        get() = File(context.filesDir, "mirakc-scan-runtime/cache/.acceptance-cancel-gr-scan")
    private val grScanStateFile: File
        get() = File(context.filesDir, "mirakc-scan-runtime/cache/.gr-scan-state")
}
