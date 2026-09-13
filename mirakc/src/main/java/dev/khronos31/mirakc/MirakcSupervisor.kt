package dev.khronos31.mirakc

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Owns the upstream mirakc process, but not tuner or smart-card descriptors. */
internal class MirakcSupervisor(
    private val context: Context,
    private val tunerDevices: () -> List<String>,
    private val openTuner: (Int, String) -> SianoUsbHandle,
    private val firmware: () -> File,
    private val onStateChanged: () -> Unit
) {
    private val lock = Any()
    private val runtimeDir = File(context.filesDir, "mirakc-runtime")
    private val epgDir = File(context.filesDir, "epg")
    private var process: NativeUsbProcess.StartedMirakc? = null
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
        openTuner = openTuner
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
        current?.let { NativeUsbProcess.stop(it.pid) }
        broker.close()
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
            restart = Thread({ restartOnWorker() }, "mirakc-supervisor-restart").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    fun status(): String = state

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
            temporaryConfig.writeText(buildConfig(cacheDir, recordingDir, strings, generation), StandardCharsets.UTF_8)
            if (!temporaryConfig.renameTo(config)) {
                throw IOException("cannot atomically install mirakc config: $config")
            }

            val launched = NativeUsbProcess.startMirakc(executable.absolutePath, config.absolutePath)
            started = launched
            synchronized(lock) {
                if (stopping) {
                    NativeUsbProcess.stop(launched.pid)
                    return
                }
                process = launched
                setStateLocked("probing (pid ${launched.pid})")
            }
            probeVersion(launched.pid)
            if (NativeUsbProcess.pollMirakc(launched.pid) != NativeUsbProcess.PollResult.ALIVE) {
                throw IOException("mirakc exited after /api/version probe")
            }
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
            started?.let {
                // Covers probe failures and startup exceptions after fork.
                NativeUsbProcess.stop(it.pid)
            }
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
        current?.let { NativeUsbProcess.stop(it.pid) }
        try {
            broker.rotateGeneration()
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
            when (NativeUsbProcess.pollMirakc(pid)) {
                NativeUsbProcess.PollResult.ALIVE -> Unit
                NativeUsbProcess.PollResult.EXITED -> throw IOException("mirakc exited during startup probe")
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
                        body.contains("\"current\":\"3.4.85\"")) return
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
            when (NativeUsbProcess.pollMirakc(started.pid)) {
                NativeUsbProcess.PollResult.ALIVE -> continue
                NativeUsbProcess.PollResult.EXITED,
                NativeUsbProcess.PollResult.ERROR -> {
                    // pollMirakc reaps an exited child; do not call
                    // nativeStop on a reaped/reused PID.
                    synchronized(lock) {
                        if (process?.pid != started.pid) return
                        process = null
                        monitor = null
                        setStateLocked("crashed (pid ${started.pid})")
                        launchRetryLocked()
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

    private fun mkdir(directory: File) {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("cannot create mirakc directory: $directory")
        }
    }

    private fun buildConfig(
        cacheDir: File,
        recordingDir: File,
        strings: File,
        generation: SianoGeneration
    ): String {
        val arib = File(context.applicationInfo.nativeLibraryDir, "libmirakc-arib.so")
        val adapter = File(context.applicationInfo.nativeLibraryDir, "libmirakc-siano-adapter.so")
        fun yamlPath(file: File): String = file.absolutePath.replace("'", "''")
        val aribPath = yamlPath(arib)
        val tunerConfig = buildString {
            if (generation.tunerCount == 0) {
                append("tuners: []\n")
            } else {
                append("tuners:\n")
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
        }
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
            |$tunerConfig|filters:
            |  service-filter:
            |    command: $aribPath filter-service --sid={{{sid}}}
            |  program-filter:
            |    command: $aribPath filter-program --sid={{{sid}}} --eid={{{eid}}} --clock-pid={{{clock_pid}}} --clock-pcr={{{clock_pcr}}} --clock-time={{{clock_time}}} --end-margin=2000
            |jobs:
            |  scan-services:
            |    command: /system/bin/timeout 30 $aribPath scan-services
            |    disabled: false
            |  sync-clocks:
            |    command: /system/bin/timeout 30 $aribPath sync-clocks
            |    disabled: false
            |  update-schedules:
            |    command: /system/bin/timeout 600 $aribPath collect-eits
            |    disabled: false
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
    }
}
