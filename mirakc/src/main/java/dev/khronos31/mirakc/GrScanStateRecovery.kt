package dev.khronos31.mirakc

import java.io.File
import java.io.IOException

/** Reconciles persisted in-flight status after a fresh Service owner is admitted. */
internal fun recoverPersistedGrScanState(
    stateFile: File,
    maxBytes: Long,
    markerNames: List<String> = listOf(".acceptance-gr-scan", ".acceptance-cancel-gr-scan")
): GrScanStatus? {
    if (!stateFile.isFile || stateFile.length() > maxBytes) return null
    val previous = runCatching { GrScanStatus.parse(stateFile.readText()) }.getOrNull() ?: return null
    if (!previous.isRunning) return null
    val interrupted = previous.asInterrupted("PROCESS_RESTARTED")
    val temporary = File(stateFile.parentFile, ".gr-scan-state.service.tmp")
    temporary.writeText(interrupted.toFileContents())
    if (!temporary.renameTo(stateFile)) throw IOException("cannot update interrupted scan state")
    markerNames.forEach { name ->
        val marker = File(stateFile.parentFile, name)
        if (marker.exists() && !marker.delete()) throw IOException("cannot clear stale scan marker")
    }
    return interrupted
}
