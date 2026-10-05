package dev.khronos31.mirakc

/** Keeps a user scan request visible while the asynchronous mirakc startup probe is in flight. */
internal class GrScanRequestLifecycle {
    sealed class Decision {
        object Busy : Decision()
        object Unavailable : Decision()
        data class Execute(val status: GrScanStatus) : Decision()
        object WaitForStartup : Decision()
    }

    private var pending: GrScanStatus? = null
    private var terminalOverride: GrScanStatus? = null

    fun request(
        processReady: Boolean,
        startupActive: Boolean,
        currentStatus: GrScanStatus?,
        nowMillis: Long = System.currentTimeMillis(),
        requestedScanId: Long? = null
    ): Decision {
        if (pending?.isRunning == true || currentStatus?.isRunning == true) return Decision.Busy
        if (!processReady && !startupActive) return Decision.Unavailable

        val queued = GrScanStatus.queued((requestedScanId ?: nowMillis).coerceAtLeast(1L))
        terminalOverride = null
        pending = queued
        return if (processReady) Decision.Execute(queued) else Decision.WaitForStartup
    }

    fun activated(scanId: Long) {
        if (pending?.scanId == scanId) pending = null
        if (terminalOverride?.scanId == scanId) terminalOverride = null
    }

    /** Keep requests pending across a coalesced restart before exposing them to mirakc. */
    fun activateWhenReady(deferForReconfigure: Boolean, activate: (GrScanStatus) -> Boolean): Boolean {
        if (deferForReconfigure) return false
        val request = pending ?: return true
        if (activate(request)) {
            activated(request.scanId)
        } else {
            triggerFailed(request.scanId)
        }
        return true
    }

    fun triggerFailed(scanId: Long) {
        if (pending?.scanId != scanId) return
        pending = null
        terminalOverride = GrScanStatus.failed(scanId, "TRIGGER_FAILED")
    }

    fun startupFailed(): GrScanStatus? {
        val request = pending ?: return null
        if (request.isRunning) {
            pending = null
            terminalOverride = GrScanStatus.failed(request.scanId, "STARTUP_FAILED")
        }
        return terminalOverride
    }

    fun cancelPending(): Boolean {
        val request = pending?.takeIf { it.isRunning } ?: return false
        pending = null
        terminalOverride = GrScanStatus.interrupted(request.scanId)
        return true
    }

    fun visibleStatus(persisted: GrScanStatus?): GrScanStatus? = pending ?: terminalOverride ?: persisted

    fun clear() {
        pending = null
        terminalOverride = null
    }
}
