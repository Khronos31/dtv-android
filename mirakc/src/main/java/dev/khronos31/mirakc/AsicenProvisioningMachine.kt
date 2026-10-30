package dev.khronos31.mirakc

internal data class AsicenUsbFunction(
    val deviceName: String,
    val productId: Int,
    val path: UsbPortPath,
    val permitted: Boolean,
    val vendorId: Int = if (productId in AsicenProvisioningMachine.LOADER_IDS) {
        AsicenProvisioningMachine.LOADER_VENDOR_ID
    } else {
        AsicenProvisioningMachine.RUNTIME_VENDOR_ID
    }
) {
    val isLoader: Boolean get() = vendorId == AsicenProvisioningMachine.LOADER_VENDOR_ID && productId in AsicenProvisioningMachine.LOADER_IDS
    val isRuntime: Boolean get() = vendorId == AsicenProvisioningMachine.RUNTIME_VENDOR_ID && productId in AsicenProvisioningMachine.RUNTIME_IDS
}

internal enum class AsicenProvisioningStage {
    WAITING, UPLOAD_PRIMARY, WAIT_PRIMARY_RUNTIME, REQUEST_SIBLING_RESTORE,
    WAIT_SIBLING_LOADER, UPLOAD_SIBLING, WAIT_RUNTIME_PAIR, START_RUNTIME, READY, ERROR, CANCELED
}

internal class AsicenProvisioningMachine(
    private val primaryPath: UsbPortPath,
    runtimeFunctions: Int? = null
) {
    var stage: AsicenProvisioningStage = AsicenProvisioningStage.WAITING
        private set
    var detail: String = "waiting for an unambiguous ASICEN USB function"
        private set
    val runtimeFunctionCount: Int? get() = runtimeFunctions
    private var primaryRuntimeGranted = false
    private var siblingRuntimeGranted = false
    private var primaryLoaderGranted = false
    private var siblingLoaderGranted = false
    private var restoreRuntimeGranted = false
    private var runtimeFunctions: Int? = runtimeFunctions

    init {
        require(runtimeFunctions == null || runtimeFunctions == 1 || runtimeFunctions == 2)
    }

    fun observe(functions: List<AsicenUsbFunction>): AsicenProvisioningStage {
        if (stage in setOf(AsicenProvisioningStage.ERROR, AsicenProvisioningStage.CANCELED, AsicenProvisioningStage.READY)) return stage
        val atPrimary = functions.filter { it.path == primaryPath }
        if (atPrimary.size > 1) return failStageInternal("ambiguous ASICEN primary USB path")
        val primary = atPrimary.singleOrNull()
        if (primary?.isRuntime == true) {
            runtimeFunctions = if (primary.productId in SINGLE_RUNTIME_IDS) 1 else 2
            if (runtimeFunctions == 2 && (primaryPath.ports.size < 2 || primaryPath.ports.last() != 1)) {
                return failStageInternal("paired ASICEN requires a canonical primary USB port ending in .1")
            }
        }
        when (stage) {
            AsicenProvisioningStage.WAITING -> if (primary?.isLoader == true) {
                stage = AsicenProvisioningStage.UPLOAD_PRIMARY
                detail = "primary loader permission required"
            } else if (primary?.isRuntime == true) {
                stage = if (runtimeFunctions == 1) AsicenProvisioningStage.START_RUNTIME
                else AsicenProvisioningStage.REQUEST_SIBLING_RESTORE
                detail = "runtime function detected"
            }
            AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME -> if (primary?.isRuntime == true) {
                stage = if (runtimeFunctions == 1) AsicenProvisioningStage.START_RUNTIME
                else AsicenProvisioningStage.REQUEST_SIBLING_RESTORE
                detail = "new primary runtime permission required"
            }
            AsicenProvisioningStage.WAIT_SIBLING_LOADER -> {
                val siblingPath = primaryPath.copy(ports = primaryPath.ports.dropLast(1) + 2)
                val sibling = functions.filter { it.path == siblingPath }
                if (sibling.size > 1) return failStageInternal("ambiguous ASICEN sibling USB path")
                if (sibling.singleOrNull()?.isLoader == true) {
                    stage = AsicenProvisioningStage.UPLOAD_SIBLING
                    detail = "sibling loader permission required"
                }
            }
            AsicenProvisioningStage.WAIT_RUNTIME_PAIR -> {
                val siblingPath = primaryPath.copy(ports = primaryPath.ports.dropLast(1) + 2)
                val sibling = functions.filter { it.path == siblingPath }
                if (sibling.size > 1) return failStageInternal("ambiguous ASICEN sibling USB path")
                if (primary?.isRuntime == true && sibling.singleOrNull()?.isRuntime == true) {
                    stage = AsicenProvisioningStage.START_RUNTIME
                    detail = "new primary and sibling runtime permissions required"
                }
            }
            else -> Unit
        }
        return stage
    }

    fun granted(function: AsicenUsbFunction): AsicenProvisioningStage {
        if (!function.permitted) return failStageInternal("USB permission denied")
        stage = when (stage) {
            AsicenProvisioningStage.UPLOAD_PRIMARY -> if (function.path == primaryPath && function.isLoader) {
                primaryLoaderGranted = true
                AsicenProvisioningStage.UPLOAD_PRIMARY
            } else return stage
            AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME -> if (function.path == primaryPath && function.isRuntime) {
                primaryRuntimeGranted = true
                if (runtimeFunctions == 1) AsicenProvisioningStage.START_RUNTIME else AsicenProvisioningStage.REQUEST_SIBLING_RESTORE
            } else return stage
            AsicenProvisioningStage.REQUEST_SIBLING_RESTORE -> if (function.path == primaryPath && function.isRuntime) {
                restoreRuntimeGranted = true
                AsicenProvisioningStage.REQUEST_SIBLING_RESTORE
            } else return stage
            AsicenProvisioningStage.UPLOAD_SIBLING -> if (function.path == siblingPath() && function.isLoader) {
                siblingLoaderGranted = true
                AsicenProvisioningStage.UPLOAD_SIBLING
            } else return stage
            AsicenProvisioningStage.START_RUNTIME -> {
                if (function.path == primaryPath && function.isRuntime) primaryRuntimeGranted = true
                if (function.path == siblingPath() && function.isRuntime) siblingRuntimeGranted = true
                AsicenProvisioningStage.START_RUNTIME
            }
            else -> return stage
        }
        detail = when (stage) {
            AsicenProvisioningStage.UPLOAD_PRIMARY -> "primary loader granted"
            AsicenProvisioningStage.UPLOAD_SIBLING -> "sibling loader granted"
            AsicenProvisioningStage.START_RUNTIME -> "runtime permission required"
            AsicenProvisioningStage.READY -> "runtime functions granted in primary/sibling order"
            else -> detail
        }
        return stage
    }

    fun invocationFinished(stageDiagnostic: String, exitCode: Int?): AsicenProvisioningStage {
        val next = when (stage) {
            AsicenProvisioningStage.UPLOAD_PRIMARY, AsicenProvisioningStage.UPLOAD_SIBLING ->
                if (stageDiagnostic == FIRMWARE_DIAGNOSTIC && exitCode == 3 &&
                    (if (stage == AsicenProvisioningStage.UPLOAD_PRIMARY) primaryLoaderGranted else siblingLoaderGranted)) {
                    if (stage == AsicenProvisioningStage.UPLOAD_PRIMARY) primaryLoaderGranted = false else siblingLoaderGranted = false
                    if (stage == AsicenProvisioningStage.UPLOAD_PRIMARY) AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME
                    else AsicenProvisioningStage.WAIT_RUNTIME_PAIR
                } else return failStageInternal("ASICEN firmware stage diagnostic or exit code mismatch")
            AsicenProvisioningStage.REQUEST_SIBLING_RESTORE ->
                if (stageDiagnostic == SIBLING_DIAGNOSTIC && exitCode == 3 && restoreRuntimeGranted) {
                    restoreRuntimeGranted = false
                    primaryRuntimeGranted = false
                    AsicenProvisioningStage.WAIT_SIBLING_LOADER
                }
                else return failStageInternal("ASICEN sibling restore diagnostic or exit code mismatch")
            AsicenProvisioningStage.START_RUNTIME -> if (exitCode == 0 && primaryRuntimeGranted &&
                (runtimeFunctions == 1 || siblingRuntimeGranted)) AsicenProvisioningStage.READY
                else return failStageInternal("ASICEN runtime command failed")
            else -> return stage
        }
        stage = next
        detail = when (next) {
            AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME -> "waiting for primary runtime at the same physical USB path"
            AsicenProvisioningStage.WAIT_SIBLING_LOADER -> "waiting for adjacent sibling loader"
            AsicenProvisioningStage.WAIT_RUNTIME_PAIR -> "waiting for both runtime functions at their original USB paths"
            AsicenProvisioningStage.READY -> "ASICEN runtime ready"
            else -> detail
        }
        return stage
    }

    fun cancel(): AsicenProvisioningStage {
        stage = AsicenProvisioningStage.CANCELED
        detail = "ASICEN provisioning canceled"
        return stage
    }

    fun failStage(reason: String): AsicenProvisioningStage = failStageInternal(reason)

    fun seedSiblingUploadAfterRestart(functions: List<AsicenUsbFunction>): AsicenProvisioningStage {
        if (stage != AsicenProvisioningStage.REQUEST_SIBLING_RESTORE) return stage
        val primary = functions.singleOrNull { it.path == primaryPath }
        val sibling = functions.singleOrNull { it.path == siblingPath() }
        if (primary?.isRuntime != true || primary.productId !in PAIRED_RUNTIME_IDS || sibling?.isLoader != true) {
            return stage
        }
        stage = AsicenProvisioningStage.UPLOAD_SIBLING
        detail = "sibling loader observed after restart; firmware upload required"
        return stage
    }

    fun needsRuntimeGrant(function: AsicenUsbFunction): Boolean = stage == AsicenProvisioningStage.START_RUNTIME &&
        function.isRuntime &&
        if (function.path == primaryPath) !primaryRuntimeGranted
        else runtimeFunctions == 2 && function.path == siblingPath() && !siblingRuntimeGranted

    fun runtimeAdmissionReady(): Boolean = stage == AsicenProvisioningStage.READY ||
        stage == AsicenProvisioningStage.START_RUNTIME && primaryRuntimeGranted &&
        (runtimeFunctions == 1 || runtimeFunctions == 2 && siblingRuntimeGranted)

    private fun siblingPath() = primaryPath.copy(ports = primaryPath.ports.dropLast(1) + 2)
    private fun failStageInternal(reason: String): AsicenProvisioningStage {
        stage = AsicenProvisioningStage.ERROR
        detail = reason
        return stage
    }

    companion object {
        const val LOADER_VENDOR_ID = 0x1738
        const val RUNTIME_VENDOR_ID = 0x0b06
        val LOADER_IDS = setOf(0x5211, 0x5216)
        val RUNTIME_IDS = setOf(0x0001, 0x0003, 0x0004, 0x0005, 0x0006)
        private val SINGLE_RUNTIME_IDS = setOf(0x0001, 0x0003)
        private val PAIRED_RUNTIME_IDS = setOf(0x0004, 0x0005, 0x0006)
        const val FIRMWARE_DIAGNOSTIC = "firmware: --fd cannot follow the loader re-enumeration; upload separately, re-grant the runtime fd, then run again"
        const val SIBLING_DIAGNOSTIC = "sibling restore: requested; grant the sibling loader FD and transfer its firmware"
    }
}
