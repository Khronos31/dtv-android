package dev.khronos31.mirakc

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserlandProductIntegrationTest {
    @Test
    fun pixGenerationUsesValidatedReceiverInventoryAndAdapterConfiguration() {
        val inventory = UserlandStatusInventory.parse(
            status("pix-userland", "65535", "[\"ISDB-T\",\"ISDB-S\"]"), "pix-userland", INSTANCE
        )
        val tuners = userlandTunersFromInventory(UserlandProduct.PIX, INSTANCE, inventory)
        val generation = UserlandGeneration(
            UserlandProduct.PIX, INSTANCE, File("/data/user/0/app/files/x1"), EPOCH, setOf("1-2.3"),
            tuners
        )
        val config = renderUserlandTunerCommands(File("/data/app/lib"), listOf(generation))
        assertTrue(config.contains("name: PIX-u-test-65535"))
        assertTrue(config.contains("types: [GR, BS, CS]"))
        assertTrue(config.contains("decoded: true"))
        assertTrue(config.contains("libmirakc-pix-adapter.so"))
        assertTrue(config.contains("--ts-program='/data/app/lib/libpix-ts.so'"))
        assertTrue(config.contains("'--receiver=65535'"))
        assertTrue(config.contains("--channel={{{channel}}} {{{extra_args}}}"))
        assertFalse(config.contains("b25"))
    }

    @Test
    fun topologyUsesExactLiveNodeMappingAndRejectsAmbiguousSysfsIdentity() {
        val root = Files.createTempDirectory("usb-topology").toFile()
        writeNode(root, "1-2.1", 1, 7, "2.1")
        writeNode(root, "1-2.2", 1, 8, "2.2")
        assertEquals(UsbPortPath(1, listOf(2, 1)), UserlandUsbTopology.resolve("/dev/bus/usb/001/007", root))
        assertEquals(UsbPortPath(1, listOf(2, 1)), UserlandUsbTopology.resolve("/dev/bus/usb/001/009", root).let {
            writeNode(root, "1-2.1", 1, 9, "2.1")
            UserlandUsbTopology.resolve("/dev/bus/usb/001/009", root)
        })
        writeNode(root, "duplicate", 1, 9, "2.1")
        assertNull(UserlandUsbTopology.resolve("/dev/bus/usb/001/009", root))
    }

    @Test
    fun selectorPairsOnlyCanonicalAdjacentFunctionsAndIgnoresArrivalOrder() {
        val primary = identity("primary", 0x0005, 1)
        val sibling = identity("sibling", 0x0005, 2)
        val selected = UserlandEnclosureSelector.asicen(listOf(sibling, primary)).single()
        assertEquals(listOf(primary, sibling), selected.devices)
        assertTrue(UserlandEnclosureSelector.asicen(listOf(primary)).isEmpty())
        assertTrue(UserlandEnclosureSelector.asicen(listOf(primary, sibling, primary.copy(deviceName = "collision"))).isEmpty())
        val otherEnclosure = identity("other", 0x0005, 2, bus = 2)
        assertTrue(UserlandEnclosureSelector.asicen(listOf(primary, otherEnclosure)).isEmpty())
        assertTrue(UserlandEnclosureSelector.asicen(listOf(
            primary.copy(vendorId = 0x1738), sibling
        )).isEmpty())
        assertTrue(UserlandEnclosureSelector.asicen(listOf(
            primary.copy(path = null), sibling
        )).isEmpty())
        val warm = AsicenProvisioningController { _, _, _ ->
            throw AssertionError("a complete warm runtime pair must bypass loader restore")
        }
        warm.refresh(listOf(primary, sibling))
        assertNull(warm.nextPermissionTarget())
        assertEquals("no cold provisioning stage", warm.status())
        assertTrue(warm.permitsRuntime(primary))
        assertTrue(warm.permitsRuntime(sibling))

        val warmSingle = AsicenProvisioningController { _, _, _ ->
            throw AssertionError("a warm single runtime must bypass loader restore")
        }
        val single = identity("warm-single", 0x0001, 3)
        warmSingle.refresh(listOf(single))
        assertNull(warmSingle.nextPermissionTarget())
        assertEquals("no cold provisioning stage", warmSingle.status())
        assertTrue(warmSingle.permitsRuntime(single))
    }

    @Test
    fun userlandProductsShareShortRuntimeRootsAndKeepInstanceNamespaces() {
        val filesDir = File("/data/user/0/dev.khronos31.mirakc/files")
        listOf(UserlandProduct.ASICEN, UserlandProduct.PIX).forEach { product ->
            val root = UserlandRuntimeLayout.rootDirectory(filesDir, product)
            val firstToken = userlandInstanceToken(product.name.lowercase(), "1-2.3")
            val secondToken = userlandInstanceToken(product.name.lowercase(), "1-4.1")
            val firstRuntime = UserlandRuntimeLayout.runtimeDirectoryForEnclosure(root, firstToken)
            val secondRuntime = UserlandRuntimeLayout.runtimeDirectoryForEnclosure(root, secondToken)
            assertEquals(root, firstRuntime)
            assertEquals(root, secondRuntime)
            val firstEndpoint = UserlandRuntimeLayout.controlEndpoint(firstRuntime, product, firstToken)
            val secondEndpoint = UserlandRuntimeLayout.controlEndpoint(secondRuntime, product, secondToken)
            assertTrue(firstEndpoint.absolutePath.toByteArray().size < 108)
            assertTrue(secondEndpoint.absolutePath.toByteArray().size < 108)
            assertTrue(firstEndpoint != secondEndpoint)
            val generation = UserlandGeneration(
                product, firstToken, firstRuntime, "epoch", setOf("1-2.3"),
                listOf(UserlandTuner("${product.name}-test", 0, setOf(BroadcastSystem.ISDB_T)))
            )
            val args = generation.adapterArguments(0)
            assertTrue(args.contains("--runtime-dir=${root.absolutePath}"))
            assertTrue(renderUserlandTunerCommands(File("/data/app/lib"), listOf(generation))
                .contains("'--runtime-dir=${root.absolutePath}'"))
        }
    }

    @Test
    fun pixUsesExactGrantedNodeWithoutSysfsAndReenumerationRetiresItsGeneration() {
        val first = UserlandUsbIdentity("/dev/bus/usb/001/011", 0x06b8, 0x102b, null)
        val second = UserlandUsbIdentity("/dev/bus/usb/001/012", 0x06b8, 0x102b, null)
        val firstEnclosure = UserlandEnclosureSelector.pix(listOf(first)).single()
        assertTrue(firstEnclosure.token != UserlandEnclosureSelector.pix(listOf(second)).single().token)
        assertTrue(UserlandEnclosureSelector.pix(listOf(first, first.copy())).isEmpty())

        val root = Files.createTempDirectory("pix-node-generation").toFile()
        var attached = listOf(first.copy(permitted = true))
        val backend = FakeBackend()
        val supervisor = UserlandProductSupervisor(
            UserlandProduct.PIX, root, { attached }, backend
        )
        val original = supervisor.startReady().single()
        attached = listOf(second.copy(permitted = true))
        val replacement = supervisor.startReady().single()
        assertTrue(original.instanceToken != replacement.instanceToken)
        assertEquals(listOf(first.deviceName, second.deviceName), backend.openedDeviceNames)
        assertEquals(1, backend.stops.get())
        supervisor.invalidateForDetach(second.deviceName)
        attached = listOf(second.copy(permitted = true))
        val reenumerated = supervisor.startReady().single()
        assertEquals(replacement.instanceToken, reenumerated.instanceToken)
        assertEquals(3, backend.starts.get())
        assertEquals(2, backend.stops.get())
        supervisor.stop()
        root.deleteRecursively()
    }

    @Test
    fun pixCommandsKeepDistinctNamesAcrossIdenticalEnclosures() {
        val enclosures = UserlandEnclosureSelector.pix(listOf(
            identity("pix-a", 0x102b, 3, vendor = 0x06b8),
            identity("pix-b", 0x102b, 3, bus = 2, vendor = 0x06b8)
        ))
        val generations = enclosures.map { enclosure ->
            UserlandGeneration(
                UserlandProduct.PIX, enclosure.token, File("/tmp/${enclosure.token}"), EPOCH,
                enclosure.devices.mapTo(linkedSetOf()) { it.deviceKey },
                userlandTunersFromInventory(
                    UserlandProduct.PIX, enclosure.token,
                    UserlandStatusInventory.parse(
                        status("pix-userland", "65535", "[\"ISDB-T\"]").replace(INSTANCE, enclosure.token),
                        "pix-userland", enclosure.token
                    )
                )
            )
        }
        val config = renderUserlandTunerCommands(File("/data/app/lib"), generations)
        assertEquals(2, Regex("name: PIX-").findAll(config).count())
        assertTrue(config.contains("name: PIX-${enclosures[0].token}-65535"))
        assertTrue(config.contains("name: PIX-${enclosures[1].token}-65535"))
    }

    @Test
    fun asicenColdPairRequiresDiagnosticsExitCodesPathsAndFreshOrderedGrants() {
        val primary = UsbPortPath(1, listOf(2, 1))
        val sibling = UsbPortPath(1, listOf(2, 2))
        val machine = AsicenProvisioningMachine(primary, runtimeFunctions = 2)
        val primaryLoader = AsicenUsbFunction("loader-p", 0x5211, primary, true)
        val primaryRuntime = AsicenUsbFunction("runtime-p", 0x0005, primary, true)
        val siblingLoader = AsicenUsbFunction("loader-s", 0x5216, sibling, true)
        val siblingRuntime = AsicenUsbFunction("runtime-s", 0x0005, sibling, true)

        assertEquals(AsicenProvisioningStage.WAITING, machine.observe(listOf(siblingLoader)))
        assertEquals(AsicenProvisioningStage.UPLOAD_PRIMARY, machine.observe(listOf(primaryLoader)))
        assertEquals(AsicenProvisioningStage.UPLOAD_PRIMARY, machine.observe(listOf(primaryLoader)))
        assertEquals(AsicenProvisioningStage.UPLOAD_PRIMARY, machine.granted(primaryLoader))
        assertEquals(AsicenProvisioningStage.ERROR, machine.invocationFinished("", 3))

        val retry = AsicenProvisioningMachine(primary, runtimeFunctions = 2)
        retry.observe(listOf(primaryLoader))
        retry.granted(primaryLoader)
        assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
            retry.invocationFinished(AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC, 3))
        assertEquals(AsicenProvisioningStage.REQUEST_SIBLING_RESTORE, retry.observe(listOf(primaryRuntime)))
        retry.granted(primaryRuntime)
        assertEquals(AsicenProvisioningStage.WAIT_SIBLING_LOADER,
            retry.invocationFinished(AsicenProvisioningMachine.SIBLING_DIAGNOSTIC, 3))
        assertEquals(AsicenProvisioningStage.WAIT_SIBLING_LOADER, retry.observe(listOf(primaryRuntime, siblingRuntime)))
        assertEquals(AsicenProvisioningStage.UPLOAD_SIBLING,
            retry.observe(listOf(primaryRuntime, siblingLoader)))
        retry.granted(siblingLoader)
        assertEquals(AsicenProvisioningStage.WAIT_RUNTIME_PAIR,
            retry.invocationFinished(AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC, 3))
        assertEquals(AsicenProvisioningStage.START_RUNTIME,
            retry.observe(listOf(siblingRuntime, primaryRuntime)))
        retry.granted(primaryRuntime)
        assertEquals(AsicenProvisioningStage.START_RUNTIME, retry.stage)
        retry.granted(siblingRuntime)
        assertEquals(AsicenProvisioningStage.START_RUNTIME, retry.stage)
        assertEquals(AsicenProvisioningStage.READY, retry.invocationFinished("", 0))
    }

    @Test
    fun asicenPermissionDenialWrongPortCancellationAndStaleCallbacksDoNotAdvance() {
        val primary = UsbPortPath(1, listOf(2, 1))
        val sibling = UsbPortPath(1, listOf(2, 2))
        val machine = AsicenProvisioningMachine(primary, runtimeFunctions = 2)
        val wrong = AsicenUsbFunction("other-loader", 0x5211, UsbPortPath(2, listOf(2, 1)), true)
        assertEquals(AsicenProvisioningStage.WAITING, machine.observe(listOf(wrong)))
        assertEquals(AsicenProvisioningStage.CANCELED, machine.cancel())

        val denied = AsicenProvisioningMachine(primary, runtimeFunctions = 2)
        val loader = AsicenUsbFunction("loader", 0x5211, primary, false)
        denied.observe(listOf(loader.copy(permitted = true)))
        assertEquals(AsicenProvisioningStage.ERROR, denied.granted(loader))

        val stale = AsicenProvisioningMachine(primary, runtimeFunctions = 2)
        val primaryLoader = loader.copy(permitted = true)
        stale.observe(listOf(primaryLoader))
        stale.granted(primaryLoader)
        assertEquals(AsicenProvisioningStage.ERROR, stale.invocationFinished(
            AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC, 0
        ))
        assertEquals(AsicenProvisioningStage.ERROR, stale.observe(listOf(
            AsicenUsbFunction("sibling-loader", 0x5216, sibling, true)
        )))
    }

    @Test
    fun asicenControllerRunsColdProvisioningInSingleAndPairedOrderThroughFreshIdentities() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val instances = mutableListOf<String>()
        val controller = AsicenProvisioningController { identity, instance, firmware ->
            calls += identity.deviceName to firmware
            instances += instance
            UserlandControl.InvocationResult(
                3,
                if (firmware) AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC
                else AsicenProvisioningMachine.SIBLING_DIAGNOSTIC
            )
        }
        val singlePath = UsbPortPath(3, listOf(4, 3))
        val loader = UserlandUsbIdentity("single-loader", 0x1738, 0x5211, singlePath, false)
        controller.refresh(listOf(loader))
        assertEquals(loader, controller.nextPermissionTarget())
        assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME, controller.permissionGranted(loader.deviceName, true))
        val singleRuntime = UserlandUsbIdentity("single-runtime-new-node", 0x0b06, 0x0001, singlePath, false)
        controller.refresh(listOf(singleRuntime))
        assertEquals(singleRuntime, controller.nextPermissionTarget())
        assertFalse(controller.permitsRuntime(singleRuntime))
        assertEquals(AsicenProvisioningStage.START_RUNTIME,
            controller.permissionGranted(singleRuntime.deviceName, true))
        controller.refresh(listOf(singleRuntime.copy(permitted = true)))
        assertTrue(controller.permitsRuntime(singleRuntime.copy(permitted = true)))
        assertEquals(userlandInstanceToken("asicen", singlePath.canonical), instances.single())
        controller.confirmReady(UserlandGeneration(
            UserlandProduct.ASICEN, userlandInstanceToken("asicen", singlePath.canonical), File("/tmp/a1"),
            EPOCH, setOf(singlePath.canonical), emptyList()
        ))
        assertTrue(controller.status().contains("ASICEN runtime ready"))

        val pairedCalls = mutableListOf<Pair<String, Boolean>>()
        val pairedInstances = mutableListOf<String>()
        val paired = AsicenProvisioningController { identity, instance, firmware ->
            pairedCalls += identity.deviceName to firmware
            pairedInstances += instance
            UserlandControl.InvocationResult(
                3,
                if (firmware) AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC
                else AsicenProvisioningMachine.SIBLING_DIAGNOSTIC
            )
        }
        val primaryPath = UsbPortPath(1, listOf(2, 1))
        val siblingPath = UsbPortPath(1, listOf(2, 2))
        val primaryLoader = UserlandUsbIdentity("primary-loader", 0x1738, 0x5211, primaryPath, false)
        paired.refresh(listOf(primaryLoader))
        assertEquals(primaryLoader, paired.nextPermissionTarget())
        paired.permissionGranted(primaryLoader.deviceName, true)
        val primaryRuntime = UserlandUsbIdentity("primary-runtime", 0x0b06, 0x0005, primaryPath, false)
        paired.refresh(listOf(primaryRuntime))
        assertFalse(paired.permitsRuntime(primaryRuntime))
        assertEquals(primaryRuntime, paired.nextPermissionTarget())
        paired.permissionGranted(primaryRuntime.deviceName, true)
        assertFalse(paired.permitsRuntime(primaryRuntime.copy(permitted = true)))
        val siblingLoader = UserlandUsbIdentity("sibling-loader", 0x1738, 0x5216, siblingPath, false)
        paired.refresh(listOf(primaryRuntime, siblingLoader))
        assertEquals(siblingLoader, paired.nextPermissionTarget())
        paired.permissionGranted(siblingLoader.deviceName, true)
        val siblingRuntime = UserlandUsbIdentity("sibling-runtime", 0x0b06, 0x0005, siblingPath, false)
        paired.refresh(listOf(siblingRuntime, primaryRuntime))
        val admittedBeforeGrants = listOf(siblingRuntime, primaryRuntime).filter {
            it.permitted && paired.permitsRuntime(it)
        }
        assertTrue(UserlandEnclosureSelector.asicen(admittedBeforeGrants).isEmpty())
        assertEquals(primaryRuntime, paired.nextPermissionTarget())
        paired.permissionGranted(primaryRuntime.deviceName, true)
        assertFalse(paired.permitsRuntime(primaryRuntime.copy(permitted = true)))
        assertEquals(siblingRuntime, paired.nextPermissionTarget())
        paired.permissionGranted(siblingRuntime.deviceName, true)
        val grantedPair = listOf(primaryRuntime.copy(permitted = true), siblingRuntime.copy(permitted = true))
        assertTrue(grantedPair.all(paired::permitsRuntime))
        assertEquals(1, UserlandEnclosureSelector.asicen(grantedPair).size)
        paired.confirmReady(UserlandGeneration(
            UserlandProduct.ASICEN, userlandInstanceToken("asicen", primaryPath.canonical), File("/tmp/a1"),
            EPOCH, setOf(primaryPath.canonical, siblingPath.canonical), emptyList()
        ))
        assertEquals(
            listOf("primary-loader" to true, "primary-runtime" to false, "sibling-loader" to true),
            pairedCalls
        )
        assertTrue(pairedInstances.all { it == userlandInstanceToken("asicen", primaryPath.canonical) })
        assertTrue(paired.status().contains("ASICEN runtime ready"))
    }

    @Test
    fun restartMidProvisionPrimaryRuntimeOnlyResumesSiblingRestore() {
        val primaryPath = UsbPortPath(6, listOf(2, 1))
        val siblingPath = primaryPath.copy(ports = listOf(2, 2))
        val primaryRuntime = UserlandUsbIdentity("restart-primary-runtime", 0x0b06, 0x0005, primaryPath, false)
        val siblingLoader = UserlandUsbIdentity("restart-sibling-loader", 0x1738, 0x5211, siblingPath, false)
        val siblingRuntime = UserlandUsbIdentity("restart-sibling-runtime", 0x0b06, 0x0005, siblingPath, false)
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        val controller = AsicenProvisioningController { identity, instance, firmware ->
            calls += Triple(identity.deviceName, instance, firmware)
            UserlandControl.InvocationResult(
                3,
                if (firmware) AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC
                else AsicenProvisioningMachine.SIBLING_DIAGNOSTIC
            )
        }
        controller.beginSession()
        controller.refresh(listOf(primaryRuntime))
        assertEquals("${primaryPath.canonical}: runtime function detected", controller.status())
        assertEquals(primaryRuntime, controller.nextPermissionTarget())
        assertFalse(controller.permitsRuntime(primaryRuntime.copy(permitted = true)))
        controller.retry()
        assertEquals(primaryRuntime, controller.nextPermissionTarget())

        finishRestartedPairedProvisioning(
            controller, primaryPath, primaryRuntime, siblingLoader, siblingRuntime, calls
        )

        assertEquals(1, controller.status().split("ASICEN runtime ready").size - 1)
        assertTrue(controller.status().contains(primaryPath.canonical))
        assertFalse(controller.status().contains(siblingPath.canonical))
    }

    @Test
    fun restartMidProvisionSiblingLoaderPresentResumesAsSibling() {
        val primaryPath = UsbPortPath(7, listOf(3, 1))
        val siblingPath = primaryPath.copy(ports = listOf(3, 2))
        val primaryRuntime = UserlandUsbIdentity("restart-b-primary-runtime", 0x0b06, 0x0005, primaryPath, false)
        val siblingLoader = UserlandUsbIdentity("restart-b-sibling-loader", 0x1738, 0x5216, siblingPath, false)
        val siblingRuntime = UserlandUsbIdentity("restart-b-sibling-runtime", 0x0b06, 0x0005, siblingPath, false)
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        val controller = AsicenProvisioningController { identity, instance, firmware ->
            calls += Triple(identity.deviceName, instance, firmware)
            UserlandControl.InvocationResult(
                3,
                if (firmware) AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC
                else AsicenProvisioningMachine.SIBLING_DIAGNOSTIC
            )
        }
        controller.beginSession()
        controller.refresh(listOf(primaryRuntime, siblingLoader))
        val instance = userlandInstanceToken("asicen", primaryPath.canonical)
        assertTrue(controller.status().startsWith("${primaryPath.canonical}:"))
        assertFalse(controller.status().contains("; ${siblingPath.canonical}:"))
        assertEquals(siblingLoader, controller.nextPermissionTarget())

        finishRestartedPairedProvisioning(
            controller, primaryPath, primaryRuntime, siblingLoader, siblingRuntime, calls,
            siblingLoaderAlreadyObserved = true
        )

        assertEquals(listOf(Triple(siblingLoader.deviceName, instance, true)), calls)
        assertFalse(controller.status().contains("ERROR"))
        assertEquals(1, controller.status().split("ASICEN runtime ready").size - 1)
    }

    @Test
    fun orphanPairedRuntimeAtPortTwoFailsClosed() {
        val orphan = UserlandUsbIdentity("orphan-paired-runtime", 0x0b06, 0x0005, UsbPortPath(8, listOf(4, 2)))
        val controller = AsicenProvisioningController { _, _, _ ->
            throw AssertionError("an orphan paired runtime must not invoke provisioning")
        }

        controller.refresh(listOf(orphan))

        assertNull(controller.nextPermissionTarget())
        assertEquals("no cold provisioning stage", controller.status())
        assertFalse(controller.permitsRuntime(orphan))
    }

    @Test
    fun noncanonicalOrAmbiguousOrMismatchedPairedRuntimesFailClosed() {
        val noncanonical = UserlandUsbIdentity(
            "noncanonical-paired-runtime", 0x0b06, 0x0005, UsbPortPath(9, listOf(4, 3))
        )
        val ambiguousPath = UsbPortPath(10, listOf(5, 1))
        val ambiguous = UserlandUsbIdentity("ambiguous-primary-a", 0x0b06, 0x0005, ambiguousPath)
        val duplicate = ambiguous.copy(deviceName = "ambiguous-primary-b")
        val mismatchedPrimary = UserlandUsbIdentity("mismatched-primary", 0x0b06, 0x0005, UsbPortPath(11, listOf(6, 1)))
        val mismatchedSibling = UserlandUsbIdentity("mismatched-sibling", 0x0b06, 0x0004, UsbPortPath(11, listOf(6, 2)))
        val controller = AsicenProvisioningController { _, _, _ ->
            throw AssertionError("ambiguous paired runtimes must not invoke provisioning")
        }

        controller.refresh(listOf(noncanonical))
        assertFalse(controller.permitsRuntime(noncanonical))
        assertEquals("no cold provisioning stage", controller.status())
        controller.refresh(listOf(ambiguous, duplicate))
        assertFalse(controller.permitsRuntime(ambiguous))
        assertEquals("no cold provisioning stage", controller.status())
        controller.refresh(listOf(mismatchedPrimary, mismatchedSibling))
        assertFalse(controller.permitsRuntime(mismatchedPrimary))
        assertFalse(controller.permitsRuntime(mismatchedSibling))
        assertEquals("no cold provisioning stage", controller.status())
        assertNull(controller.nextPermissionTarget())
    }

    @Test
    fun coldSingleOnHubPortTwoRetainsTheLoaderInstanceThroughRuntimeGrant() {
        val path = UsbPortPath(4, listOf(2, 2))
        val instances = mutableListOf<String>()
        val controller = AsicenProvisioningController { _, instance, _ ->
            instances += instance
            UserlandControl.InvocationResult(3, AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC)
        }
        val loader = UserlandUsbIdentity("port-two-loader", 0x1738, 0x5211, path)
        controller.refresh(listOf(loader))
        assertEquals(loader, controller.nextPermissionTarget())
        assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME, controller.permissionGranted(loader.deviceName, true))
        val runtime = UserlandUsbIdentity("port-two-runtime", 0x0b06, 0x0001, path)
        controller.refresh(listOf(runtime))
        assertEquals(runtime, controller.nextPermissionTarget())
        assertEquals(AsicenProvisioningStage.START_RUNTIME, controller.permissionGranted(runtime.deviceName, true))
        val expected = userlandInstanceToken("asicen", path.canonical)
        assertEquals(listOf(expected), instances)
        controller.confirmReady(UserlandGeneration(UserlandProduct.ASICEN, expected, File("/tmp/a1"), EPOCH,
            setOf(path.canonical), emptyList()))
        assertTrue(controller.status().contains("ASICEN runtime ready"))
    }

    @Test
    fun asicenSingleOnHubPortTwoAndThreeUsesCanonicalColdGenerationAndExactVidPid() {
        val hubPortThree = UsbPortPath(2, listOf(3, 3))
        val invocations = mutableListOf<Pair<String, Boolean>>()
        val controller = AsicenProvisioningController { _, instance, firmware ->
            invocations += instance to firmware
            UserlandControl.InvocationResult(3, AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC)
        }
        val wrongVendor = UserlandUsbIdentity("wrong-vendor", 0x9999, 0x5211, hubPortThree)
        controller.refresh(listOf(wrongVendor))
        assertNull(controller.nextPermissionTarget())
        assertFalse(controller.permitsRuntime(wrongVendor))

        val loader = UserlandUsbIdentity("hub-loader", 0x1738, 0x5216, hubPortThree)
        controller.refresh(listOf(loader))
        assertEquals(loader, controller.nextPermissionTarget())
        assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
            controller.permissionGranted(loader.deviceName, true))
        assertEquals(userlandInstanceToken("asicen", hubPortThree.canonical) to true, invocations.single())

        val wrongPath = UserlandUsbIdentity("wrong-path-runtime", 0x0b06, 0x0003, hubPortThree.copy(ports = listOf(3, 2)))
        controller.refresh(listOf(wrongPath))
        assertNull(controller.nextPermissionTarget())
        assertTrue(controller.status().contains("waiting for primary runtime"))

        val runtime = UserlandUsbIdentity("hub-runtime-new-node", 0x0b06, 0x0003, hubPortThree)
        controller.refresh(listOf(runtime))
        assertEquals(runtime, controller.nextPermissionTarget())
        assertEquals(AsicenProvisioningStage.START_RUNTIME, controller.permissionGranted(runtime.deviceName, true))
        assertTrue(controller.permitsRuntime(runtime.copy(permitted = true)))
        controller.confirmReady(UserlandGeneration(
            UserlandProduct.ASICEN, userlandInstanceToken("asicen", hubPortThree.canonical), File("/tmp/a1"),
            EPOCH, setOf(hubPortThree.canonical), emptyList()
        ))
        assertTrue(controller.status().contains("ASICEN runtime ready"))
    }

    @Test
    fun asicenIndependentSinglesDoNotShareSiblingPathOwnership() {
        val portThree = UsbPortPath(4, listOf(2, 3))
        val portTwo = UsbPortPath(4, listOf(2, 2))
        val invocations = mutableListOf<Pair<String, String>>()
        val controller = AsicenProvisioningController { identity, instance, _ ->
            invocations += identity.deviceName to instance
            UserlandControl.InvocationResult(3, AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC)
        }
        val loaderThree = UserlandUsbIdentity("port-three-loader", 0x1738, 0x5211, portThree)
        val loaderTwo = UserlandUsbIdentity("port-two-loader", 0x1738, 0x5216, portTwo)
        controller.refresh(listOf(loaderThree, loaderTwo))

        assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
            controller.permissionGranted(loaderThree.deviceName, true))
        assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
            controller.permissionGranted(loaderTwo.deviceName, true))
        assertEquals(
            listOf(
                loaderThree.deviceName to userlandInstanceToken("asicen", portThree.canonical),
                loaderTwo.deviceName to userlandInstanceToken("asicen", portTwo.canonical)
            ),
            invocations
        )

        val runtimeThree = UserlandUsbIdentity("port-three-runtime", 0x0b06, 0x0001, portThree)
        val runtimeTwo = UserlandUsbIdentity("port-two-runtime", 0x0b06, 0x0003, portTwo)
        controller.refresh(listOf(runtimeThree, runtimeTwo))
        assertEquals(AsicenProvisioningStage.START_RUNTIME,
            controller.permissionGranted(runtimeThree.deviceName, true))
        assertEquals(AsicenProvisioningStage.START_RUNTIME,
            controller.permissionGranted(runtimeTwo.deviceName, true))
        assertTrue(controller.permitsRuntime(runtimeThree.copy(permitted = true)))
        assertTrue(controller.permitsRuntime(runtimeTwo.copy(permitted = true)))
        controller.confirmReady(UserlandGeneration(
            UserlandProduct.ASICEN, userlandInstanceToken("asicen", portThree.canonical), File("/tmp/a1"),
            EPOCH, setOf(portThree.canonical), emptyList()
        ))
        controller.confirmReady(UserlandGeneration(
            UserlandProduct.ASICEN, userlandInstanceToken("asicen", portTwo.canonical), File("/tmp/a1"),
            EPOCH, setOf(portTwo.canonical), emptyList()
        ))
        assertEquals(2, controller.status().split("ASICEN runtime ready").size - 1)
    }

    @Test
    fun asicenIndependentSinglesAtPortsOneAndTwoRemainSeparateInEitherArrivalOrder() {
        fun verify(portTwoArrivesFirst: Boolean) {
            val portOne = UsbPortPath(5, listOf(2, 1))
            val portTwo = UsbPortPath(5, listOf(2, 2))
            val calls = mutableListOf<Pair<String, String>>()
            val controller = AsicenProvisioningController { identity, instance, _ ->
                calls += identity.deviceName to instance
                UserlandControl.InvocationResult(3, AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC)
            }
            val loaderOne = UserlandUsbIdentity("port-one-loader", 0x1738, 0x5211, portOne)
            val loaderTwo = UserlandUsbIdentity("port-two-loader", 0x1738, 0x5216, portTwo)
            if (portTwoArrivesFirst) controller.refresh(listOf(loaderTwo))
            controller.refresh(listOf(loaderOne, loaderTwo))

            assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
                controller.permissionGranted(loaderOne.deviceName, true))
            if (!portTwoArrivesFirst) {
                assertNull(controller.permissionTicket(loaderTwo.deviceName))
            }
            val runtimeOne = UserlandUsbIdentity("port-one-runtime", 0x0b06, 0x0001, portOne)
            controller.refresh(listOf(runtimeOne, loaderTwo))
            assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
                controller.permissionGranted(loaderTwo.deviceName, true))
            val runtimeTwo = UserlandUsbIdentity("port-two-runtime", 0x0b06, 0x0003, portTwo)
            controller.refresh(listOf(runtimeOne, runtimeTwo))
            assertEquals(AsicenProvisioningStage.START_RUNTIME,
                controller.permissionGranted(runtimeOne.deviceName, true))
            assertEquals(AsicenProvisioningStage.START_RUNTIME,
                controller.permissionGranted(runtimeTwo.deviceName, true))
            assertTrue(controller.permitsRuntime(runtimeOne.copy(permitted = true)))
            assertTrue(controller.permitsRuntime(runtimeTwo.copy(permitted = true)))
            controller.confirmReady(UserlandGeneration(
                UserlandProduct.ASICEN, userlandInstanceToken("asicen", portOne.canonical), File("/tmp/a1"),
                EPOCH, setOf(portOne.canonical), emptyList()
            ))
            controller.confirmReady(UserlandGeneration(
                UserlandProduct.ASICEN, userlandInstanceToken("asicen", portTwo.canonical), File("/tmp/a1"),
                EPOCH, setOf(portTwo.canonical), emptyList()
            ))
            assertEquals(2, controller.status().split("ASICEN runtime ready").size - 1)
            assertEquals(
                setOf(
                    loaderOne.deviceName to userlandInstanceToken("asicen", portOne.canonical),
                    loaderTwo.deviceName to userlandInstanceToken("asicen", portTwo.canonical)
                ),
                calls.toSet()
            )
        }

        verify(portTwoArrivesFirst = false)
        verify(portTwoArrivesFirst = true)
    }

    @Test
    fun detachedTerminalAsicenOwnersDoNotBlockSamePathColdReconnect() {
        fun verify(
            expectedInvocations: Int,
            terminalize: (AsicenProvisioningController, UserlandUsbIdentity) -> Unit
        ) {
            val path = UsbPortPath(6, listOf(3, 4))
            var invocations = 0
            val controller = AsicenProvisioningController { _, _, _ ->
                invocations += 1
                UserlandControl.InvocationResult(3, AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC)
            }
            val original = UserlandUsbIdentity("old-loader", 0x1738, 0x5211, path)
            controller.refresh(listOf(original))
            terminalize(controller, original)
            controller.refresh(emptyList())

            val reconnected = UserlandUsbIdentity("new-loader", 0x1738, 0x5216, path)
            controller.refresh(listOf(reconnected))
            assertEquals(reconnected, controller.nextPermissionTarget())
            assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
                controller.permissionGranted(reconnected.deviceName, true))
            assertEquals(expectedInvocations, invocations)
        }

        verify(1) { controller, _ -> controller.cancel() }
        verify(1) { controller, loader -> controller.failed(loader.deviceName, "failed") }
        verify(2) { controller, loader ->
            controller.permissionGranted(loader.deviceName, true)
            val path = loader.path!!
            val runtime = UserlandUsbIdentity("old-runtime", 0x0b06, 0x0001, path)
            controller.refresh(listOf(runtime))
            controller.permissionGranted(runtime.deviceName, true)
            controller.confirmReady(UserlandGeneration(
                UserlandProduct.ASICEN, userlandInstanceToken("asicen", path.canonical),
                File("/tmp/a1"), EPOCH, setOf(path.canonical), emptyList()
            ))
        }
    }

    @Test
    fun readyAsicenOwnerResetsForRapidColdReplugAndPermissionTicketChangesGeneration() {
        val path = UsbPortPath(7, listOf(2, 3))
        var uploads = 0
        val controller = AsicenProvisioningController { _, _, firmware ->
            if (firmware) uploads += 1
            UserlandControl.InvocationResult(3, AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC)
        }
        val loader = UserlandUsbIdentity("same-usb-node", 0x1738, 0x5211, path)
        controller.refresh(listOf(loader))
        val originalTicket = controller.permissionTicket(loader.deviceName)
        assertEquals(loader, controller.nextPermissionTarget())
        controller.permissionGranted(loader.deviceName, true)
        val runtime = UserlandUsbIdentity(loader.deviceName, 0x0b06, 0x0001, path)
        controller.refresh(listOf(runtime))
        controller.permissionGranted(runtime.deviceName, true)
        controller.confirmReady(UserlandGeneration(
            UserlandProduct.ASICEN, userlandInstanceToken("asicen", path.canonical), File("/tmp/a1"), EPOCH,
            setOf(path.canonical), emptyList()
        ))
        assertTrue(controller.status().contains("ASICEN runtime ready"))

        controller.refresh(listOf(loader))
        assertEquals(loader, controller.nextPermissionTarget())
        assertTrue(originalTicket != controller.permissionTicket(loader.deviceName))
        assertEquals(AsicenProvisioningStage.WAIT_PRIMARY_RUNTIME,
            controller.permissionGranted(loader.deviceName, true))
        assertEquals(2, uploads)
    }

    @Test
    fun explicitServerSessionResetsCanceledAsicenOwnerButNotError() {
        val path = UsbPortPath(8, listOf(2, 3))
        val loader = UserlandUsbIdentity("session-loader", 0x1738, 0x5211, path)
        val controller = AsicenProvisioningController { _, _, _ ->
            UserlandControl.InvocationResult(3, AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC)
        }
        controller.refresh(listOf(loader))
        controller.cancel()
        assertNull(controller.nextPermissionTarget())
        controller.beginSession()
        assertEquals(loader, controller.nextPermissionTarget())
        controller.failed(loader.deviceName, "provisioning error")
        controller.beginSession()
        assertNull(controller.nextPermissionTarget())
        controller.retry()
        controller.refresh(listOf(loader))
        assertEquals(loader, controller.nextPermissionTarget())
    }

    @Test
    fun canceledAsicenInvocationCompletionCannotAdvanceRetiredStage() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val path = UsbPortPath(9, listOf(2, 3))
        val loader = UserlandUsbIdentity("blocking-loader", 0x1738, 0x5211, path)
        val controller = AsicenProvisioningController { _, _, _ ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            UserlandControl.InvocationResult(3, AsicenProvisioningMachine.FIRMWARE_DIAGNOSTIC)
        }
        controller.refresh(listOf(loader))
        var result: AsicenProvisioningStage? = null
        val grant = Thread { result = controller.permissionGranted(loader.deviceName, true) }
        grant.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        controller.cancel()
        release.countDown()
        grant.join(3000)
        assertFalse(grant.isAlive)
        assertEquals(AsicenProvisioningStage.CANCELED, result)
        assertTrue(controller.status().contains("provisioning canceled"))
    }

    @Test
    fun asicenControllerDenialAndStalePermissionDoNotRepeatOrAdvanceAStage() {
        val controller = AsicenProvisioningController { _, _, _ ->
            throw AssertionError("denied and stale permission results must not start a stage")
        }
        val path = UsbPortPath(1, listOf(2, 1))
        val loader = UserlandUsbIdentity("loader", 0x1738, 0x5211, path, false)
        controller.refresh(listOf(loader))
        assertEquals(loader, controller.nextPermissionTarget())
        assertEquals(AsicenProvisioningStage.ERROR, controller.permissionGranted(loader.deviceName, false))
        assertNull(controller.nextPermissionTarget())
        controller.retry()
        controller.refresh(listOf(loader))
        assertEquals(loader, controller.nextPermissionTarget())
        controller.refresh(emptyList())
        assertNull(controller.permissionGranted(loader.deviceName, true))
        assertNull(controller.nextPermissionTarget())
    }

    @Test
    fun sharedOwnerRetainsSingleProcessAndClosesExactUsbGenerationOnDetach() {
        val identity = identity("pix", 0x102b, 1, vendor = 0x06b8)
        val enclosure = UserlandEnclosure(UserlandProduct.PIX, INSTANCE, listOf(identity))
        val backend = FakeBackend()
        val owner = UserlandEnclosureOwner(UserlandProduct.PIX, enclosure, File("/tmp/pix-test"), backend)
        val generation = owner.startOrGet()
        assertEquals(listOf(100), backend.startedFds)
        assertEquals(1, backend.starts.get())
        assertEquals(generation, owner.startOrGet())
        assertEquals(1, backend.starts.get())
        owner.invalidateForDetach("pix")
        assertEquals(1, backend.stops.get())
        assertEquals(1, backend.closedHandles.get())
        assertNull(owner.startOrGet())
    }

    @Test
    fun sharedOwnerRetiresLateReadinessWithoutPublishingStaleInventory() {
        val identity = identity("pix", 0x102b, 1, vendor = 0x06b8)
        val enclosure = UserlandEnclosure(UserlandProduct.PIX, INSTANCE, listOf(identity))
        val backend = FakeBackend(statusBlocked = true)
        val owner = UserlandEnclosureOwner(
            UserlandProduct.PIX, enclosure, File("/tmp/pix-late-test"), backend, readyTimeoutMs = 10_000
        )
        val finished = CountDownLatch(1)
        Thread { owner.startOrGet(); finished.countDown() }.start()
        assertTrue(backend.statusEntered.await(2, TimeUnit.SECONDS))
        owner.invalidateForDetach("pix")
        backend.releaseStatus.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(1, backend.stops.get())
        assertEquals(1, backend.closedHandles.get())
    }

    private fun finishRestartedPairedProvisioning(
        controller: AsicenProvisioningController,
        primaryPath: UsbPortPath,
        primaryRuntime: UserlandUsbIdentity,
        siblingLoader: UserlandUsbIdentity,
        siblingRuntime: UserlandUsbIdentity,
        calls: MutableList<Triple<String, String, Boolean>>,
        siblingLoaderAlreadyObserved: Boolean = false
    ) {
        val siblingPath = primaryPath.copy(ports = primaryPath.ports.dropLast(1) + 2)
        val instance = userlandInstanceToken("asicen", primaryPath.canonical)
        if (siblingLoaderAlreadyObserved) {
            assertEquals(siblingLoader, controller.nextPermissionTarget())
            assertEquals(AsicenProvisioningStage.WAIT_RUNTIME_PAIR,
                controller.permissionGranted(siblingLoader.deviceName, true))
            assertEquals(listOf(Triple(siblingLoader.deviceName, instance, true)), calls)
        } else {
            assertEquals(AsicenProvisioningStage.WAIT_SIBLING_LOADER,
                controller.permissionGranted(primaryRuntime.deviceName, true))
            assertEquals(listOf(Triple(primaryRuntime.deviceName, instance, false)), calls)

            controller.refresh(listOf(primaryRuntime.copy(permitted = true), siblingLoader))
            assertEquals(siblingLoader, controller.nextPermissionTarget())
            assertEquals(AsicenProvisioningStage.WAIT_RUNTIME_PAIR,
                controller.permissionGranted(siblingLoader.deviceName, true))
            assertEquals(listOf(
                Triple(primaryRuntime.deviceName, instance, false),
                Triple(siblingLoader.deviceName, instance, true)
            ), calls)
        }

        controller.refresh(listOf(primaryRuntime, siblingRuntime))
        assertEquals(primaryRuntime, controller.nextPermissionTarget())
        assertEquals(AsicenProvisioningStage.START_RUNTIME,
            controller.permissionGranted(primaryRuntime.deviceName, true))
        assertEquals(siblingRuntime, controller.nextPermissionTarget())
        assertEquals(AsicenProvisioningStage.START_RUNTIME,
            controller.permissionGranted(siblingRuntime.deviceName, true))
        val runtimePair = listOf(primaryRuntime.copy(permitted = true), siblingRuntime.copy(permitted = true))
        assertTrue(runtimePair.all(controller::permitsRuntime))
        assertEquals(1, UserlandEnclosureSelector.asicen(runtimePair).size)
        controller.confirmReady(UserlandGeneration(
            UserlandProduct.ASICEN, instance, File("/tmp/a1"), EPOCH,
            setOf(primaryPath.canonical, siblingPath.canonical), emptyList()
        ))
        assertTrue(controller.status().contains("ASICEN runtime ready"))
    }

    private inner class FakeBackend(private val statusBlocked: Boolean = false) : UserlandOwnerBackend {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val closedHandles = AtomicInteger()
        val statusEntered = CountDownLatch(1)
        val releaseStatus = CountDownLatch(if (statusBlocked) 1 else 0)
        val startedFds = mutableListOf<Int>()
        val openedDeviceNames = mutableListOf<String>()
        override fun open(device: UserlandUsbIdentity): UserlandOwnedUsb {
            openedDeviceNames += device.deviceName
            return object : UserlandOwnedUsb {
                override val fd = 100
                override fun close() { closedHandles.incrementAndGet() }
            }
        }
        override fun start(
            product: UserlandProduct,
            instance: String,
            runtimeDir: File,
            handles: List<UserlandOwnedUsb>
        ): UserlandDaemonProcess {
            starts.incrementAndGet()
            startedFds += handles.map { it.fd }
            return UserlandDaemonProcess(41, null)
        }
        override fun status(product: UserlandProduct, instance: String, runtimeDir: File): UserlandStatusInventory {
            statusEntered.countDown()
            releaseStatus.await(2, TimeUnit.SECONDS)
            val json = this@UserlandProductIntegrationTest.status(product.statusProduct, "4", "[\"ISDB-T\"]")
                .replace(INSTANCE, instance)
            return UserlandStatusInventory.parse(json, product.statusProduct, instance)
        }
        override fun poll(pid: Int) = NativeUsbProcess.PollResult.ALIVE
        override fun stop(pid: Int) { stops.incrementAndGet() }
    }

    private fun writeNode(root: File, name: String, bus: Int, devnum: Int, devpath: String) {
        val node = File(root, name).apply { mkdirs() }
        File(node, "busnum").writeText("$bus\n")
        File(node, "devnum").writeText("$devnum\n")
        File(node, "devpath").writeText("$devpath\n")
    }

    private fun identity(name: String, productId: Int, port: Int, bus: Int = 1, vendor: Int = 0x0b06) =
        UserlandUsbIdentity(name, vendor, productId, UsbPortPath(bus, listOf(2, port)))

    private fun status(product: String, receiverId: String, systems: String) = """
        {"schema":{"name":"userland.status","major":1,"minor":0},
         "producer":{"product":"$product","version":"1.0","core_version":null},
         "context":{"epoch":"$EPOCH","instance":"$INSTANCE","state":"ready","last_error":null},
         "scope":{"kind":"enclosure","id":null},
         "topology":{"state":"complete","expected_function_count":1},
         "receivers":[{"descriptor":{"receiver":$receiverId,"systems":$systems},
           "present":true,"state":"free"}]}
    """.trimIndent()

    private companion object {
        const val INSTANCE = "u-test"
        const val EPOCH = "0123456789abcdef0123456789abcdef"
    }
}
