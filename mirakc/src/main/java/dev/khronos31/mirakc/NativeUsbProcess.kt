package dev.khronos31.mirakc

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal object NativeUsbProcess {
    init {
        System.loadLibrary("usb_process")
    }

    fun start(
        executable: String,
        firmware: String,
        channel: Int,
        usbFd: Int,
        readerFd: Int = -1,
        readerExecutable: String? = null
    ): StartedProcess {
        val handles = nativeStart(
            executable, firmware, channel, usbFd, readerFd, readerExecutable.orEmpty()
        )
        check(handles != null && handles.size == 3) { "Unable to start siano-ts" }
        val readFd = handles[0]
        val pid = handles[1]
        val diagnosticsFd = handles[2]
        check(readFd >= 0 && diagnosticsFd >= 0 && pid > 0) { "Invalid siano-ts process handle" }
        return StartedProcess(
            pid,
            ParcelFileDescriptor.adoptFd(readFd),
            ParcelFileDescriptor.adoptFd(diagnosticsFd)
        )
    }

    fun stop(pid: Int) = nativeStop(pid)

    /** Drain Siano stderr so a noisy child cannot block on its pipe. */
    fun startDiagnostics(process: StartedProcess, context: String): Thread {
        val reader = Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(process.diagnostics).use { input ->
                    val buffer = ByteArray(1024)
                    val line = StringBuilder(MAX_DIAGNOSTIC_LINE)
                    var loggedBytes = 0
                    fun emitLine() {
                        if (line.isEmpty() || loggedBytes >= MAX_DIAGNOSTICS_BYTES) {
                            line.setLength(0)
                            return
                        }
                        val text = line.toString()
                        val remaining = MAX_DIAGNOSTICS_BYTES - loggedBytes
                        val clipped = if (text.length > remaining) text.substring(0, remaining) else text
                        if (clipped.isNotEmpty()) {
                            Log.e(TAG, "siano[$context, pid=${process.pid}] $clipped")
                            loggedBytes += clipped.length
                        }
                        line.setLength(0)
                    }
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        for (offset in 0 until count) {
                            when (val value = buffer[offset].toInt() and 0xff) {
                                '\n'.code -> emitLine()
                                '\r'.code -> Unit
                                else -> if (line.length < MAX_DIAGNOSTIC_LINE) {
                                    line.append(if (value in 0x20..0x7e) value.toChar() else '?')
                                }
                            }
                        }
                    }
                    emitLine()
                }
            } catch (_: IOException) {
                // Closing the descriptor is the normal stop path.
            }
        }, "siano-diagnostics-${process.pid}").apply { isDaemon = true }
        synchronized(process.diagnosticsLock) {
            check(!process.diagnosticsFinished.get() && process.diagnosticsThread == null) {
                "Siano diagnostics reader already started or finished"
            }
            process.diagnosticsThread = reader
            reader.start()
        }
        return reader
    }

    /** Stop the reader only after a bounded chance to drain post-exit stderr. */
    fun finishDiagnostics(process: StartedProcess) {
        if (!process.diagnosticsFinished.compareAndSet(false, true)) return
        val reader = synchronized(process.diagnosticsLock) { process.diagnosticsThread }
        if (reader != null && reader !== Thread.currentThread()) {
            try {
                reader.join(DIAGNOSTICS_DRAIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            process.diagnostics.close()
        } catch (_: IOException) {
        }
        synchronized(process.diagnosticsLock) {
            if (process.diagnosticsThread === reader) process.diagnosticsThread = null
        }
    }

    fun startMirakc(executable: String, config: String): StartedMirakc {
        val handles = nativeStartMirakc(executable, config)
        check(handles != null && handles.size == 2) { "Unable to start upstream mirakc" }
        val outputFd = handles[0]
        val pid = handles[1]
        check(pid > 0) { "Unable to start upstream mirakc" }
        check(outputFd >= 0) { "Invalid upstream mirakc diagnostics handle" }
        return StartedMirakc(pid, ParcelFileDescriptor.adoptFd(outputFd))
    }

    sealed class PollResult {
        data object ALIVE : PollResult()
        data class EXITED(val code: Int?, val signal: Int?) : PollResult()
        data object ERROR : PollResult()
    }

    fun pollMirakc(pid: Int): PollResult = when (val result = nativePollMirakc(pid)) {
        0 -> PollResult.ALIVE
        1 -> PollResult.EXITED(code = null, signal = null)
        in 2..257 -> PollResult.EXITED(code = result - 2, signal = null)
        in -129..-2 -> PollResult.EXITED(code = null, signal = -result - 2)
        else -> PollResult.ERROR
    }

    fun pollSiano(pid: Int): PollResult = when (val result = nativePollSiano(pid)) {
        0 -> PollResult.ALIVE
        1 -> PollResult.EXITED(code = null, signal = null)
        in 2..257 -> PollResult.EXITED(code = result - 2, signal = null)
        in -129..-2 -> PollResult.EXITED(code = null, signal = -result - 2)
        else -> PollResult.ERROR
    }

    fun startPx4d(
        executable: String,
        firmware: String,
        baseSerial: String,
        runtimeDir: String,
        firstUsbFd: Int,
        secondUsbFd: Int
    ): StartedPx4d {
        val handles = nativeStartPx4d(
            executable, firmware, baseSerial, runtimeDir, firstUsbFd, secondUsbFd
        )
        check(handles != null && handles.size == 2) { "Unable to start px4d" }
        val outputFd = handles[0]
        val pid = handles[1]
        check(outputFd >= 0 && pid > 0) { "Invalid px4d process handle" }
        return StartedPx4d(pid, ParcelFileDescriptor.adoptFd(outputFd))
    }

    fun pollPx4d(pid: Int): PollResult = when (val result = nativePollPx4d(pid)) {
        0 -> PollResult.ALIVE
        1 -> PollResult.EXITED(code = null, signal = null)
        in 2..257 -> PollResult.EXITED(code = result - 2, signal = null)
        in -129..-2 -> PollResult.EXITED(code = null, signal = -result - 2)
        else -> PollResult.ERROR
    }

    /** Stop the direct px4d owner. Returns true when SIGKILL was required. */
    fun stopPx4d(pid: Int): Boolean = when (nativeStopPx4d(pid)) {
        0 -> false
        1 -> true
        else -> false
    }

    data class StartedProcess(
        val pid: Int,
        val output: ParcelFileDescriptor,
        val diagnostics: ParcelFileDescriptor
    ) {
        internal val diagnosticsLock = Any()
        internal var diagnosticsThread: Thread? = null
        internal val diagnosticsFinished = AtomicBoolean(false)
    }
    data class StartedMirakc(val pid: Int, val output: ParcelFileDescriptor)
    data class StartedPx4d(val pid: Int, val output: ParcelFileDescriptor)

    private const val MAX_DIAGNOSTICS_BYTES = 64 * 1024
    private const val MAX_DIAGNOSTIC_LINE = 512
    private const val DIAGNOSTICS_DRAIN_TIMEOUT_MS = 500L
    private const val TAG = "SianoTunerBroker"

    private external fun nativeStart(
        executable: String,
        firmware: String,
        channel: Int,
        usbFd: Int,
        readerFd: Int,
        readerExecutable: String
    ): IntArray?
    private external fun nativeStop(pid: Int)
    private external fun nativeStartMirakc(executable: String, config: String): IntArray?
    private external fun nativePollMirakc(pid: Int): Int
    private external fun nativePollSiano(pid: Int): Int
    private external fun nativeStartPx4d(
        executable: String,
        firmware: String,
        baseSerial: String,
        runtimeDir: String,
        firstUsbFd: Int,
        secondUsbFd: Int
    ): IntArray?
    private external fun nativePollPx4d(pid: Int): Int
    private external fun nativeStopPx4d(pid: Int): Int
}
