package dev.khronos31.mirakc

import android.os.ParcelFileDescriptor

internal object NativeUsbProcess {
    init {
        System.loadLibrary("usb_process")
    }

    fun start(executable: String, firmware: String, channel: Int, usbFd: Int): StartedProcess {
        val handles = nativeStart(executable, firmware, channel, usbFd)
        check(handles != null && handles.size == 2) { "Unable to start siano-ts" }
        val readFd = handles[0]
        val pid = handles[1]
        check(readFd >= 0 && pid > 0) { "Invalid siano-ts process handle" }
        return StartedProcess(pid, ParcelFileDescriptor.adoptFd(readFd))
    }

    fun stop(pid: Int) = nativeStop(pid)

    data class StartedProcess(val pid: Int, val output: ParcelFileDescriptor)

    private external fun nativeStart(executable: String, firmware: String, channel: Int, usbFd: Int): IntArray?
    private external fun nativeStop(pid: Int)
}
