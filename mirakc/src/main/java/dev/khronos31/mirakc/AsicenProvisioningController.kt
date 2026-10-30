package dev.khronos31.mirakc

internal class AsicenProvisioningController(
    private val invoke: (UserlandUsbIdentity, String, Boolean) -> UserlandControl.InvocationResult
) {
    private data class Owner(
        val path: UsbPortPath,
        val instance: String,
        val generation: Long,
        val machine: AsicenProvisioningMachine
    )
    private val lock = Any()
    private val owners = linkedMapOf<String, Owner>()
    private val runningStages = mutableSetOf<Long>()
    private var nextGeneration = 0L
    private var currentFunctions: List<AsicenUsbFunction> = emptyList()
    private var currentIdentities: List<UserlandUsbIdentity> = emptyList()

    fun refresh(identities: List<UserlandUsbIdentity>) {
        synchronized(lock) {
            currentIdentities = identities
            currentFunctions = identities.filter {
                it.path != null && (
                    it.vendorId == AsicenProvisioningMachine.LOADER_VENDOR_ID &&
                        it.productId in AsicenProvisioningMachine.LOADER_IDS ||
                        it.vendorId == AsicenProvisioningMachine.RUNTIME_VENDOR_ID &&
                        it.productId in AsicenProvisioningMachine.RUNTIME_IDS
                    )
            }.map {
                AsicenUsbFunction(it.deviceName, it.productId, it.path!!, it.permitted, it.vendorId)
            }
            owners.entries.removeAll { (_, owner) ->
                owner.machine.stage in TERMINAL_STAGES && owner.generation !in runningStages &&
                    currentFunctions.none { it.path == owner.path }
            }
            owners.values.filter { owner ->
                owner.machine.stage == AsicenProvisioningStage.READY && owner.generation !in runningStages &&
                    currentFunctions.any { it.path == owner.path && it.isLoader }
            }.map { it.path.canonical }.forEach(owners::remove)
            owners.values.forEach { owner ->
                val related = currentFunctions.filter { ownsPath(owner, it.path) }
                owner.machine.observe(related)
            }
            createIncompletePairedRuntimeOwners()
            val loaders = currentFunctions.filter { it.isLoader }
            loaders.filterNot { loader -> isKnownSiblingLoader(loader, loaders) }.forEach { loader ->
                val key = loader.path.canonical
                owners.getOrPut(key) {
                    newOwner(loader.path, userlandInstanceToken("asicen", key))
                }
            }
            owners.values.forEach { owner ->
                val related = currentFunctions.filter { ownsPath(owner, it.path) }
                owner.machine.observe(related)
            }
        }
    }

    fun nextPermissionTarget(): UserlandUsbIdentity? = synchronized(lock) {
        owners.values.sortedBy { it.instance }.firstNotNullOfOrNull { owner ->
            if (owner.generation in runningStages) return@firstNotNullOfOrNull null
            if (owner.machine.stage == AsicenProvisioningStage.START_RUNTIME) {
                val paths = listOf(owner.path, siblingPath(owner.path))
                for (path in paths) {
                    val function = currentFunctions.singleOrNull {
                        ownsPath(owner, it.path) && it.path == path &&
                            it.productId in AsicenProvisioningMachine.RUNTIME_IDS
                    } ?: continue
                    if (!owner.machine.needsRuntimeGrant(function)) continue
                    return@firstNotNullOfOrNull currentIdentities.singleOrNull { it.deviceName == function.deviceName }
                }
                return@firstNotNullOfOrNull null
            }
            val expected = when (owner.machine.stage) {
                AsicenProvisioningStage.UPLOAD_PRIMARY -> owner.path to AsicenProvisioningMachine.LOADER_IDS
                AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
                AsicenProvisioningStage.REQUEST_SIBLING_RESTORE -> owner.path to AsicenProvisioningMachine.RUNTIME_IDS
                AsicenProvisioningStage.WAIT_SIBLING_LOADER,
                AsicenProvisioningStage.UPLOAD_SIBLING -> siblingPath(owner.path) to AsicenProvisioningMachine.LOADER_IDS
                else -> null
            } ?: return@firstNotNullOfOrNull null
            val function = currentFunctions.singleOrNull {
                ownsPath(owner, it.path) && it.path == expected.first && it.productId in expected.second
            }
                ?: return@firstNotNullOfOrNull null
            currentIdentities.singleOrNull { it.deviceName == function.deviceName }
        }
    }

    fun permissionGranted(deviceName: String, granted: Boolean): AsicenProvisioningStage? {
        val matched = synchronized(lock) {
            val function = currentFunctions.singleOrNull { it.deviceName == deviceName } ?: return null
            val owner = owners.values.filter { ownsPath(it, function.path) }.singleOrNull()
                ?: return null
            if (!runningStages.add(owner.generation)) return owner.machine.stage
            owner.machine.observe(currentFunctions.filter { ownsPath(owner, it.path) })
            val stage = owner.machine.granted(function.copy(permitted = granted))
            PermissionResult(owner, owner.machine, stage,
                currentIdentities.singleOrNull { it.deviceName == deviceName })
        }
        val (owner, machine, stage, identity) = matched
        if (stage == AsicenProvisioningStage.UPLOAD_PRIMARY || stage == AsicenProvisioningStage.UPLOAD_SIBLING) {
            if (identity == null) {
                synchronized(lock) { runningStages.remove(owner.generation) }
                return stage
            }
            val result = try {
                invoke(identity, owner.instance, true)
            } catch (error: Exception) {
                return invocationFailed(owner, machine, stage, error)
            }
            val diagnostic = result.output.lineSequence()
                .firstOrNull { it == AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC }.orEmpty()
            return invocationFinished(owner, machine, stage, diagnostic, result.exitCode)
        }
        if (stage == AsicenProvisioningStage.REQUEST_SIBLING_RESTORE) {
            if (identity == null) {
                synchronized(lock) { runningStages.remove(owner.generation) }
                return stage
            }
            val result = try {
                invoke(identity, owner.instance, false)
            } catch (error: Exception) {
                return invocationFailed(owner, machine, stage, error)
            }
            val diagnostic = result.output.lineSequence()
                .firstOrNull { it == AsicenProvisioningMachine.SIBLING_DIAGNOSTIC }.orEmpty()
            return invocationFinished(owner, machine, stage, diagnostic, result.exitCode)
        }
        return synchronized(lock) {
            runningStages.remove(owner.generation)
            machine.stage
        }
    }

    fun confirmReady(generation: UserlandGeneration) {
        if (generation.product != UserlandProduct.ASICEN) return
        synchronized(lock) {
            owners.values.firstOrNull { it.instance == generation.instanceToken }?.let { owner ->
                if (owner.machine.stage == AsicenProvisioningStage.START_RUNTIME) owner.machine.invocationFinished("", 0)
            }
        }
    }

    fun failed(deviceName: String, reason: String) {
        synchronized(lock) {
            val function = currentFunctions.singleOrNull { it.deviceName == deviceName } ?: return
            owners.values.filter { ownsPath(it, function.path) }.singleOrNull()
                ?.machine?.failStage(reason)
        }
    }

    fun permissionTicket(deviceName: String): String? = synchronized(lock) {
        val function = currentFunctions.singleOrNull { it.deviceName == deviceName } ?: return null
        val owner = owners.values.filter { ownsPath(it, function.path) }.singleOrNull() ?: return null
        if (nextPermissionTarget()?.deviceName != deviceName) return null
        "${owner.instance}:${owner.generation}:${owner.machine.stage.name}:$deviceName"
    }

    fun permitsRuntime(identity: UserlandUsbIdentity): Boolean = synchronized(lock) {
        val path = identity.path ?: return false
        if (!identity.permitted || identity.vendorId != AsicenProvisioningMachine.RUNTIME_VENDOR_ID ||
            identity.productId !in AsicenProvisioningMachine.RUNTIME_IDS) return false
        val matching = owners.values.filter { ownsPath(it, path) }
        matching.singleOrNull()?.machine?.runtimeAdmissionReady() ?: (
            matching.isEmpty() && (identity.productId in SINGLE_RUNTIME_IDS || isCompleteWarmPair(identity))
        )
    }

    fun retry() {
        synchronized(lock) {
            if (runningStages.isNotEmpty() || owners.values.none {
                    it.machine.stage == AsicenProvisioningStage.ERROR || it.machine.stage == AsicenProvisioningStage.CANCELED
                }) return
            owners.clear()
        }
    }

    fun beginSession() {
        synchronized(lock) {
            owners.entries.toList().forEach { (key, owner) ->
                if (owner.machine.stage == AsicenProvisioningStage.CANCELED && owner.generation !in runningStages) {
                    val replacement = newOwner(owner.path, owner.instance)
                    owners[key] = replacement
                    replacement.machine.observe(currentFunctions.filter { ownsPath(replacement, it.path) })
                }
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            owners.values.forEach { it.machine.cancel() }
        }
    }

    fun status(): String = synchronized(lock) {
        owners.values.joinToString("; ") { "${it.path.canonical}: ${it.machine.detail}" }
            .ifEmpty { "no cold provisioning stage" }
    }

    private fun siblingPath(path: UsbPortPath) = path.copy(ports = path.ports.dropLast(1) + 2)

    private fun createIncompletePairedRuntimeOwners() {
        val pairedPrimaries = currentFunctions.filter {
            it.isRuntime && it.productId in PAIRED_RUNTIME_IDS &&
                it.path.ports.size >= 2 && it.path.ports.lastOrNull() == 1
        }
        pairedPrimaries.forEach { primary ->
            val siblingPath = siblingPath(primary.path)
            val siblings = currentFunctions.filter { it.path == siblingPath }
            if (currentFunctions.count { it.path == primary.path } != 1 ||
                !(siblings.isEmpty() || siblings.size == 1 && siblings.single().isLoader) ||
                currentFunctions.count {
                    it.isRuntime && it.productId in PAIRED_RUNTIME_IDS && it.path.enclosure == primary.path.enclosure
                } != 1
            ) return@forEach
            if (owners.values.any {
                    it.path == primary.path || it.path == siblingPath ||
                        ownsPath(it, primary.path) || ownsPath(it, siblingPath)
                }) return@forEach
            val owner = newOwner(primary.path, userlandInstanceToken("asicen", primary.path.canonical))
            owners[primary.path.canonical] = owner
            val related = currentFunctions.filter { ownsPath(owner, it.path) }
            owner.machine.observe(related)
            if (siblings.singleOrNull()?.isLoader == true) owner.machine.seedSiblingUploadAfterRestart(related)
        }
    }

    private fun isCompleteWarmPair(identity: UserlandUsbIdentity): Boolean {
        val path = identity.path ?: return false
        val primaryPath = when {
            path.ports.size < 2 -> return false
            path.ports.lastOrNull() == 1 -> path
            path.ports.lastOrNull() == 2 -> path.copy(ports = path.ports.dropLast(1) + 1)
            else -> return false
        }
        val siblingPath = siblingPath(primaryPath)
        val functions = currentFunctions.filter {
            it.isRuntime && it.productId in PAIRED_RUNTIME_IDS && it.path.enclosure == primaryPath.enclosure
        }
        if (functions.size != 2) return false
        val primary = functions.singleOrNull { it.path == primaryPath } ?: return false
        val sibling = functions.singleOrNull { it.path == siblingPath } ?: return false
        return primary.permitted && sibling.permitted &&
            primary.productId == identity.productId && sibling.productId == identity.productId
    }

    private data class PermissionResult(
        val owner: Owner,
        val machine: AsicenProvisioningMachine,
        val stage: AsicenProvisioningStage,
        val identity: UserlandUsbIdentity?
    )

    private fun newOwner(path: UsbPortPath, instance: String): Owner = Owner(
        path, instance, nextGeneration++, AsicenProvisioningMachine(path)
    )

    private fun invocationFinished(
        owner: Owner,
        machine: AsicenProvisioningMachine,
        expectedStage: AsicenProvisioningStage,
        diagnostic: String,
        exitCode: Int?
    ): AsicenProvisioningStage = synchronized(lock) {
        val result = if (isCurrent(owner, machine, expectedStage)) {
            machine.invocationFinished(diagnostic, exitCode)
        } else machine.stage
        runningStages.remove(owner.generation)
        result
    }

    private fun invocationFailed(
        owner: Owner,
        machine: AsicenProvisioningMachine,
        expectedStage: AsicenProvisioningStage,
        error: Exception
    ): AsicenProvisioningStage = synchronized(lock) {
        val result = if (isCurrent(owner, machine, expectedStage)) {
            machine.failStage(error.message ?: error.javaClass.simpleName)
        } else machine.stage
        runningStages.remove(owner.generation)
        result
    }

    private fun isCurrent(
        owner: Owner,
        machine: AsicenProvisioningMachine,
        expectedStage: AsicenProvisioningStage
    ): Boolean = owners[owner.path.canonical] === owner && owner.generation in runningStages &&
        owner.machine === machine && machine.stage == expectedStage

    private fun ownsPath(owner: Owner, path: UsbPortPath): Boolean = path == owner.path ||
        ownerCanOwnSibling(owner) && path == siblingPath(owner.path) &&
        owners.values.none { it.instance != owner.instance && it.path == path }

    private fun ownerCanOwnSibling(owner: Owner): Boolean = owner.path.ports.size >= 2 &&
        owner.path.ports.lastOrNull() == 1 && owner.machine.runtimeFunctionCount != 1

    private fun isKnownSiblingLoader(loader: AsicenUsbFunction, loaders: List<AsicenUsbFunction>): Boolean {
        if (loader.path.ports.lastOrNull() != 2) return false
        val primary = loader.path.copy(ports = loader.path.ports.dropLast(1) + 1)
        return owners.values.any { it.path == primary && ownsPath(it, loader.path) } ||
            loaders.any { it.path == primary }
    }

    private companion object {
        val SINGLE_RUNTIME_IDS = setOf(0x0001, 0x0003)
        val PAIRED_RUNTIME_IDS = setOf(0x0004, 0x0005, 0x0006)
        val TERMINAL_STAGES = setOf(
            AsicenProvisioningStage.READY,
            AsicenProvisioningStage.ERROR,
            AsicenProvisioningStage.CANCELED
        )
    }
}
