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

internal fun renderMirakcJobCommands(aribPath: String): String = buildString {
    append("  scan-services:\n")
    append("    command: /system/bin/timeout 30 $aribPath scan-services")
    append(MIRAKC_JOB_FILTER_ARGS)
    append("\n    disabled: false\n")
    append("  sync-clocks:\n")
    append("    command: /system/bin/timeout 30 $aribPath sync-clocks")
    append(MIRAKC_JOB_FILTER_ARGS)
    append("\n    disabled: false\n")
    append("  update-schedules:\n")
    append("    command: /system/bin/timeout 600 $aribPath collect-eits")
    append(MIRAKC_JOB_FILTER_ARGS)
    append("\n    disabled: false")
}

/** Owns the upstream mirakc process, but not tuner or smart-card descriptors. */
internal class MirakcSupervisor(
    private val context: Context,
    private val tunerDevices: () -> List<String>,
    private val openTuner: (Int, String) -> SianoUsbHandle,
    private val openReader: () -> SianoReaderHandle?,
    private val firmware: () -> File,
    private val px4Devices: () -> List<Px4DeviceIdentity>,
    private val openPx4: (Px4DeviceIdentity) -> Px4UsbHandle,
    private val px4Firmware: () -> File,
    private val onStateChanged: () -> Unit
) {
    private val lock = Any()
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
    private var stopping = false
    @Volatile private var state = "stopped"
    private val broker = SianoTunerBroker(
        sianoExecutable = { File(context.applicationInfo.nativeLibraryDir, "libsiano-ts.so") },
        firmware = firmware,
        tunerDevices = tunerDevices,
        openTuner = openTuner,
        openReader = openReader
    )
    private val px4 = Px4DaemonSupervisor(
        executable = { File(context.applicationInfo.nativeLibraryDir, "libpx4d.so") },
        firmware = px4Firmware,
        identities = px4Devices,
        openDevice = openPx4,
        runtimeDir = File(context.filesDir, "p4"),
        onStateChanged = onStateChanged,
        onDaemonFailure = {
            Log.e(TAG, "PX4 daemon failed; scheduling mirakc reconfigure")
            reconfigure()
        }
    )

    fun start() {
        synchronized(lock) {
            if (process != null || startup?.isAlive == true) return
            stopping = false
            crashRestartCount = 0
            setStateLocked("starting")
            startup = Thread({ startOnWorker() }, "mirakc-supervisor-start").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    fun stop() {
        val current: NativeUsbProcess.StartedMirakc?
        synchronized(lock) {
            stopping = true
            startup?.interrupt()
            startup = null
            monitor?.interrupt()
            monitor = null
            // Invalidate an in-flight USB reconfiguration before closing the
            // broker. Its worker must never reopen the abstract socket after
            // shutdown has completed.
            restart?.interrupt()
            restart = null
            crashRestart?.interrupt()
            crashRestart = null
            reconfigurePending = false
            current = process
            process = null
            setStateLocked("stopped")
        }
        // nativeStop terminates the complete process group.  Do this outside
        // the lock so a state callback cannot ever wait on process teardown.
        current?.let { stopStarted(it, "service-stop") }
        broker.close()
        px4.stop()
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
            setStateLocked("restarting for USB change")
            Log.i(TAG, "breadcrumb reconfigure scheduled")
            restart = Thread({ restartOnWorker() }, "mirakc-supervisor-restart").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    fun status(): String = state

    fun px4Status(): String = px4.status()

    private fun startOnWorker() {
        var started: NativeUsbProcess.StartedMirakc? = null
        try {
            synchronized(lock) {
                if (stopping) return
            }
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val executable = nativeDir.resolve("libmirakc.so")
            if (!executable.isFile) throw IOException("upstream mirakc is not packaged: $executable")

            // Keep one stable app-private runtime tree.  The EPG tree is
            // intentionally separate and persistent across process restarts.
            mkdir(runtimeDir)
            mkdir(epgDir)
            val cacheDir = epgDir.resolve("cache")
            val recordingDir = epgDir.resolve("recordings")
            mkdir(cacheDir)
            mkdir(recordingDir)
            synchronized(lock) {
                if (stopping) return
            }
            val generation = broker.start()
            val px4Generation = px4.startOrGet()
            val abortAfterBrokerStart = synchronized(lock) { stopping }
            if (abortAfterBrokerStart) {
                // stop() may have raced with broker.start(); do not leave a
                // newly-created abstract socket behind after shutdown.
                broker.close()
                return
            }
            val strings = runtimeDir.resolve("strings.yml")
            context.assets.open("mirakc-strings.yml").use { input ->
                strings.outputStream().use { output -> input.copyTo(output) }
            }
            val config = runtimeDir.resolve("config.yml")
            val temporaryConfig = config.resolveSibling("config.yml.tmp")
            temporaryConfig.writeText(
                buildConfig(cacheDir, recordingDir, strings, generation, px4Generation),
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
                    setStateLocked("probing (pid ${launched.pid})")
                    false
                }
            }
            if (abortStartup) {
                stopStarted(launched, "stop-during-startup")
                return
            }
            probeVersion(launched.pid)
            when (val result = NativeUsbProcess.pollMirakc(launched.pid)) {
                NativeUsbProcess.PollResult.ALIVE -> Unit
                is NativeUsbProcess.PollResult.EXITED ->
                    throw IOException("mirakc exited after /api/version probe (${describe(result)})")
                NativeUsbProcess.PollResult.ERROR ->
                    throw IOException("unable to poll mirakc after /api/version probe")
            }
            Log.i(TAG, "breadcrumb probe success pid=${launched.pid}")
            val rerun = synchronized(lock) {
                if (process?.pid != launched.pid || stopping) return
                // Read and clear this while startup is still marked alive so
                // a permission event cannot be lost between those operations.
                val restartActive = restart?.isAlive == true
                val pending = reconfigurePending
                if (!restartActive) reconfigurePending = false
                startup = null
                setStateLocked("running (pid ${launched.pid})")
                monitor = Thread({ monitor(launched) }, "mirakc-supervisor-monitor").also {
                    it.isDaemon = true
                    it.start()
                }
                // A restart worker leaves the flag for its finally block,
                // which clears its marker before scheduling the follow-up.
                pending && !restartActive
            }
            // A restart worker cannot recursively schedule itself while its
            // marker is alive; its finally block handles that coalesced event.
            if (rerun && synchronized(lock) { restart?.isAlive != true }) reconfigure()
        } catch (error: Exception) {
            Log.e(TAG, "breadcrumb startup failure")
            started?.let { stopStarted(it, "startup-failure") }
            synchronized(lock) {
                if (stopping) {
                    startup = null
                    return
                }
                if (process?.pid == started?.pid) process = null
                startup = null
                setStateLocked("error (startup): ${error.message ?: error.javaClass.simpleName}")
                // A failed initial start has no monitor to trigger retries.
                if (crashRestart == null) launchRetryLocked()
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
            startOnWorker()
        } finally {
            val rerun = synchronized(lock) {
                restart = null
                val pending = reconfigurePending
                reconfigurePending = false
                pending
            }
            if (rerun) reconfigure()
        }
    }

    private fun probeVersion(pid: Int) {
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
                val connection = URL("http://127.0.0.1:40772/api/version").openConnection() as HttpURLConnection
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.requestMethod = "GET"
                try {
                    val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    if (connection.responseCode == HttpURLConnection.HTTP_OK &&
                        body.contains("\"current\":\"3.4.86\"")) return
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

    private fun monitor(started: NativeUsbProcess.StartedMirakc) {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
            when (val result = NativeUsbProcess.pollMirakc(started.pid)) {
                NativeUsbProcess.PollResult.ALIVE -> continue
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
                        setStateLocked(
                            if (result is NativeUsbProcess.PollResult.EXITED) {
                                "crashed (pid ${started.pid}, ${describe(result)})"
                            } else {
                                "crashed (pid ${started.pid}, poll error)"
                            }
                        )
                        launchRetryLocked()
                    }
                    finishDiagnostics(started)
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
                setStateLocked("restarting after crash (attempt ${attempt + 1}/$MAX_CRASH_RESTARTS)")
            }
            broker.rotateGeneration()
            synchronized(lock) {
                if (stopping) return
            }
            startOnWorker()
        } catch (_: InterruptedException) {
            // stop() interrupts the bounded backoff during service teardown.
        } catch (error: Exception) {
            synchronized(lock) {
                if (!stopping) setStateLocked("error (crash restart): ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            val rerun = synchronized(lock) {
                if (startup === worker) startup = null
                val wasRetryWorker = crashRestart === worker
                if (wasRetryWorker) crashRestart = null
                if (wasRetryWorker && process == null) launchRetryLocked()
                val pending = reconfigurePending
                reconfigurePending = false
                pending
            }
            if (rerun) reconfigure()
        }
    }

    private fun launchRetryLocked() {
        if (stopping || crashRestart != null || crashRestartCount >= MAX_CRASH_RESTARTS) return
        val attempt = crashRestartCount++
        crashRestart = Thread(
            { crashRestartOnWorker(attempt) },
            "mirakc-supervisor-crash-restart"
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun setStateLocked(value: String) {
        state = value
        // The callback only rebuilds the service status text and never takes
        // this supervisor's lock, so state notifications cannot deadlock.
        onStateChanged()
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
        cacheDir: File,
        recordingDir: File,
        strings: File,
        generation: SianoGeneration,
        px4Generation: Px4Generation?
    ): String {
        val arib = File(context.applicationInfo.nativeLibraryDir, "libmirakc-arib.so")
        val adapter = File(context.applicationInfo.nativeLibraryDir, "libmirakc-siano-adapter.so")
        val px4Adapter = File(context.applicationInfo.nativeLibraryDir, "libmirakc-px4-adapter.so")
        fun yamlPath(file: File): String = file.absolutePath.replace("'", "''")
        val aribPath = yamlPath(arib)
        val tunerConfig = buildString {
            if (generation.tunerCount == 0 && px4Generation == null) {
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
            px4Generation?.let { px4 ->
                val px4Ts = yamlPath(File(context.applicationInfo.nativeLibraryDir, "libpx4-ts.so"))
                repeat(px4.terrestrialReceivers.size) { index ->
                    append("  - name: PX4-GR-${px4.terrestrialReceivers[index]}\n")
                    append("    types: [GR]\n")
                    append("    command: ")
                    append(yamlPath(px4Adapter))
                    append(" --px4-ts=")
                    append(px4Ts)
                    append(" --device=")
                    append(px4.baseSerial)
                    append(" --receiver=")
                    append(px4.terrestrialReceivers[index])
                    append(" --runtime-dir=")
                    append(yamlPath(px4.runtimeDir))
                    append(" --channel={{{channel}}}\n")
                }
                repeat(px4.satelliteReceivers.size) { index ->
                    append("  - name: PX4-S-${px4.satelliteReceivers[index]}\n")
                    append("    types: [BS, CS]\n")
                    append("    command: ")
                    append(yamlPath(px4Adapter))
                    append(" --px4-ts=")
                    append(px4Ts)
                    append(" --device=")
                    append(px4.baseSerial)
                    append(" --receiver=")
                    append(px4.satelliteReceivers[index])
                    append(" --runtime-dir=")
                    append(yamlPath(px4.runtimeDir))
                    append(" --channel={{{channel}}} {{{extra_args}}}\n")
                }
            }
        }
        val satelliteChannelConfig = if (px4Generation == null) {
            ""
        } else {
            renderPx4SatelliteChannelConfig()
        }
        val jobsConfig = renderMirakcJobCommands(aribPath)
        return """
            |epg:
            |  cache-dir: '${yamlPath(cacheDir)}'
            |server:
            |  addrs:
            |    - http: '0.0.0.0:40772'
            |channels:
            |  - name: TOKYO MX
            |    type: GR
            |    channel: '16'
            |  - name: フジテレビジョン
            |    type: GR
            |    channel: '21'
            |  - name: TBS
            |    type: GR
            |    channel: '22'
            |  - name: テレビ東京
            |    type: GR
            |    channel: '23'
            |  - name: テレビ朝日
            |    type: GR
            |    channel: '24'
            |  - name: 日本テレビ
            |    type: GR
            |    channel: '25'
            |  - name: NHK Eテレ東京
            |    type: GR
            |    channel: '26'
            |  - name: NHK総合・東京
            |    type: GR
            |    channel: '27'
            |  - name: チバテレビ
            |    type: GR
            |    channel: '30'
            |  - name: tvk
            |    type: GR
            |    channel: '31'
            |  - name: テレ玉
            |    type: GR
            |    channel: '32'
            |$satelliteChannelConfig$tunerConfig|filters:
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
            |  basedir: '${yamlPath(recordingDir)}'
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
        const val TAG = "MirakcSupervisor"
    }
}
