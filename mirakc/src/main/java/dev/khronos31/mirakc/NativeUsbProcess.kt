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
        val pid = nativeStartMirakc(executable, config)
        check(pid > 0) { "Unable to start upstream mirakc" }
        return StartedMirakc(pid)
    }

    enum class PollResult { ALIVE, EXITED, ERROR }

    fun pollMirakc(pid: Int): PollResult = when (nativePollMirakc(pid)) {
        0 -> PollResult.ALIVE
        1 -> PollResult.EXITED
        else -> PollResult.ERROR
    }

    data class StartedProcess(val pid: Int, val output: ParcelFileDescriptor)
    data class StartedMirakc(val pid: Int)

    private external fun nativeStart(executable: String, firmware: String, channel: Int, usbFd: Int, readerFd: Int): IntArray?
    private external fun nativeStop(pid: Int)
    private external fun nativeStartMirakc(executable: String, config: String): Int
    private external fun nativePollMirakc(pid: Int): Int
}
