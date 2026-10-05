package dev.khronos31.mirakc

/** Pure policy for resuming the original scan request after each USB grant. */
internal class ScanPermissionContinuation {
    sealed interface Decision {
        data class RequestPermission(val deviceId: String) : Decision
        data object StartScan : Decision
        data object Denied : Decision
        data object Canceled : Decision
        data object NotPending : Decision
    }

    private var pending: Boolean = false
    private var operationId: Long = 0L

    val isPending: Boolean
        @Synchronized get() = pending

    @Synchronized
    fun begin(missingPermissionDeviceIds: List<String>): Decision {
        return begin(0L, missingPermissionDeviceIds)
    }

    @Synchronized
    fun begin(scanOperationId: Long, missingPermissionDeviceIds: List<String>): Decision {
        pending = true
        operationId = scanOperationId
        return next(missingPermissionDeviceIds)
    }

    @Synchronized
    fun onPermissionResult(granted: Boolean, stillMissingDeviceIds: List<String>): Decision {
        return onPermissionResult(operationId, granted, stillMissingDeviceIds)
    }

    @Synchronized
    fun onPermissionResult(scanOperationId: Long, granted: Boolean, stillMissingDeviceIds: List<String>): Decision {
        if (!pending) return Decision.NotPending
        if (scanOperationId != operationId) return Decision.NotPending
        if (!granted) {
            pending = false
            operationId = 0L
            return Decision.Denied
        }
        return next(stillMissingDeviceIds)
    }

    @Synchronized
    fun cancel(): Decision {
        return cancel(operationId)
    }

    @Synchronized
    fun cancel(scanOperationId: Long): Decision {
        if (!pending) return Decision.NotPending
        if (scanOperationId != operationId) return Decision.NotPending
        pending = false
        operationId = 0L
        return Decision.Canceled
    }

    @Synchronized
    fun processStopped(): Decision = cancel()

    private fun next(missingPermissionDeviceIds: List<String>): Decision {
        val nextDevice = missingPermissionDeviceIds.firstOrNull()
        if (nextDevice != null) return Decision.RequestPermission(nextDevice)
        pending = false
        operationId = 0L
        return Decision.StartScan
    }
}
