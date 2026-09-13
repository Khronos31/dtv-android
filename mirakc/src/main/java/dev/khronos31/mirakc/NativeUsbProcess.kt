package dev.khronos31.mirakc

import android.os.ParcelFileDescriptor

internal object NativeUsbProcess {
    init {
        System.loadLibrary("usb_process")
    }

    fun start(executable: String, firmware: String, channel: Int, usbFd: Int, readerFd: Int = -1): StartedProcess {
        val handles = nativeStart(executable, firmware, channel, usbFd, readerFd)
        check(handles != null && handles.size == 2) { "Unable to start siano-ts" }
        val readFd = handles[0]
        val pid = handles[1]
        check(readFd >= 0 && pid > 0) { "Invalid siano-ts process handle" }
        return StartedProcess(pid, ParcelFileDescriptor.adoptFd(readFd))
    }

    fun stop(pid: Int) = nativeStop(pid)

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

    data class StartedProcess(val pid: Int, val output: ParcelFileDescriptor)
    data class StartedMirakc(val pid: Int, val output: ParcelFileDescriptor)

    private external fun nativeStart(executable: String, firmware: String, channel: Int, usbFd: Int, readerFd: Int): IntArray?
    private external fun nativeStop(pid: Int)
    private external fun nativeStartMirakc(executable: String, config: String): IntArray?
    private external fun nativePollMirakc(pid: Int): Int
}
