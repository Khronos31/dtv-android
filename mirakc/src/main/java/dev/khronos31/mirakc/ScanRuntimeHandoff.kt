package dev.khronos31.mirakc

/** Serializes cancellation against the only two irreversible scan handoffs. */
internal class ScanRuntimeHandoff {
    internal sealed interface CancelDecision {
        data object CanceledPreparation : CancelDecision
        data class CancelNative(val scanId: Long) : CancelDecision
        data object AlreadyFinishing : CancelDecision
        data object NotCancelable : CancelDecision
    }

    private sealed interface State {
        data object Idle : State
        data class Preparing(val scanId: Long) : State
        data class Native(val scanId: Long) : State
        data class Finishing(val scanId: Long) : State
    }

    private var state: State = State.Idle

    @Synchronized
    fun begin(scanId: Long): Boolean {
        if (scanId <= 0 || state !is State.Idle) return false
        state = State.Preparing(scanId)
        return true
    }

    @Synchronized
    fun ownsPreparation(scanId: Long): Boolean = state == State.Preparing(scanId)

    @Synchronized
    fun ownsFinishing(scanId: Long): Boolean = state == State.Finishing(scanId)

    @Synchronized
    fun canCancel(scanId: Long): Boolean =
        state == State.Preparing(scanId) || state == State.Native(scanId)

    @Synchronized
    fun restoreNativeAfterCancelFailure(scanId: Long): Boolean {
        if (state != State.Finishing(scanId) && state !is State.Idle) return false
        state = State.Native(scanId)
        return true
    }

    /** Called while the Service request lock also validates owner generation. */
    @Synchronized
    fun handoffToNative(scanId: Long, start: () -> Boolean): Boolean {
        if (state != State.Preparing(scanId)) return false
        if (!start()) {
            state = State.Idle
            return false
        }
        state = State.Native(scanId)
        return true
    }

    /** Claim the short durable commit so Cancel cannot race a settings write. */
    @Synchronized
    fun claimPreparedCommit(scanId: Long): Boolean {
        if (state != State.Preparing(scanId)) return false
        state = State.Finishing(scanId)
        return true
    }

    @Synchronized
    fun cancel(scanId: Long): CancelDecision = when (val current = state) {
        State.Idle -> CancelDecision.NotCancelable
        is State.Preparing -> if (current.scanId != scanId) CancelDecision.NotCancelable else {
            state = State.Idle
            CancelDecision.CanceledPreparation
        }
        is State.Native -> if (current.scanId != scanId) CancelDecision.NotCancelable else {
            // Keep admission closed while the supervisor performs cancellation
            // outside the request lock. A retry cannot race this completion.
            state = State.Finishing(scanId)
            CancelDecision.CancelNative(scanId)
        }
        is State.Finishing -> if (current.scanId == scanId) CancelDecision.AlreadyFinishing
            else CancelDecision.NotCancelable
    }

    @Synchronized
    fun finish(scanId: Long): Boolean {
        val activeId = when (val current = state) {
            State.Idle -> return false
            is State.Preparing -> current.scanId
            is State.Native -> current.scanId
            is State.Finishing -> current.scanId
        }
        if (activeId != scanId) return false
        state = State.Idle
        return true
    }
}
