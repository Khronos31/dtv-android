package dev.khronos31.mirakc

/** Adds CCID readers to setup permission requests without treating them as tuners. */
internal fun <T> combineUsbPermissionTargets(
    tunerTargets: List<T>,
    readerTargets: List<T>,
    deviceId: (T) -> String
): List<T> = (tunerTargets + readerTargets).distinctBy(deviceId).sortedBy(deviceId)
