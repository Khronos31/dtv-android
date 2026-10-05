package dev.khronos31.mirakc

import java.io.File

/** Runtime used by one owner of the tuner stack. Scan mode never exposes the public API. */
internal enum class MirakcRuntimeMode {
    SCAN_ONLY,
    PUBLIC
}

internal data class MirakcRuntimeLayout(
    val runtimeDirectory: File,
    val cacheDirectory: File,
    val recordingDirectory: File,
    val listenAddress: String
)

internal fun mirakcRuntimeLayout(filesDirectory: File, mode: MirakcRuntimeMode): MirakcRuntimeLayout {
    val runtime = when (mode) {
        MirakcRuntimeMode.SCAN_ONLY -> File(filesDirectory, "mirakc-scan-runtime")
        MirakcRuntimeMode.PUBLIC -> File(filesDirectory, "mirakc-runtime")
    }
    val cache = when (mode) {
        MirakcRuntimeMode.SCAN_ONLY -> File(filesDirectory, "mirakc-scan-runtime/cache")
        MirakcRuntimeMode.PUBLIC -> File(filesDirectory, "epg/cache")
    }
    val recordings = when (mode) {
        MirakcRuntimeMode.SCAN_ONLY -> File(filesDirectory, "mirakc-scan-runtime/recordings")
        MirakcRuntimeMode.PUBLIC -> File(filesDirectory, "epg/recordings")
    }
    return MirakcRuntimeLayout(
        runtimeDirectory = runtime,
        cacheDirectory = cache,
        recordingDirectory = recordings,
        listenAddress = when (mode) {
            MirakcRuntimeMode.SCAN_ONLY -> "127.0.0.1:40773"
            MirakcRuntimeMode.PUBLIC -> "0.0.0.0:40772"
        }
    )
}

internal fun renderMirakcJobCommands(
    aribPath: String,
    epgIntervalMinutes: Int = EpgUpdateIntervalSettings.DEFAULT_MINUTES,
    enabled: Boolean = true
): String {
    require(epgIntervalMinutes in EpgUpdateIntervalSettings.MIN_MINUTES..EpgUpdateIntervalSettings.MAX_MINUTES) {
        "EPG interval must be ${EpgUpdateIntervalSettings.MIN_MINUTES}..${EpgUpdateIntervalSettings.MAX_MINUTES} minutes"
    }
    val disabled = !enabled
    return buildString {
        append("  scan-services:\n")
        append("    command: /system/bin/timeout 30 $aribPath scan-services")
        append(MIRAKC_JOB_FILTER_ARGS)
        append("\n    disabled: $disabled\n")
        append("  sync-clocks:\n")
        append("    command: /system/bin/timeout 30 $aribPath sync-clocks")
        append(MIRAKC_JOB_FILTER_ARGS)
        append("\n    disabled: $disabled\n")
        append("  update-schedules:\n")
        append("    command: /system/bin/timeout 600 $aribPath collect-eits")
        append(MIRAKC_JOB_FILTER_ARGS)
        append("\n    schedule: '@every ${epgIntervalMinutes}m'\n    disabled: $disabled")
    }
}
