package dev.khronos31.mirakc

import java.io.File

/** Maps the app-owned runtime root to the upstream px4-userland endpoint layout. */
internal object Px4RuntimeLayout {
    const val CONTROL_ENDPOINT = "control.sock"
    const val STREAM_ENDPOINT = "stream.sock"

    /**
     * px4-userland adds `px4-userland/<instance>` itself. Keep the Android
     * runtime directory shared by all enclosure processes.
     */
    fun runtimeDirectoryForEnclosure(
        runtimeRoot: File,
        @Suppress("UNUSED_PARAMETER") instanceToken: String
    ): File = runtimeRoot

    fun endpoint(runtimeDirectory: File, instanceToken: String, endpointName: String): File =
        File(runtimeDirectory, "px4-userland/$instanceToken/$endpointName")
}
