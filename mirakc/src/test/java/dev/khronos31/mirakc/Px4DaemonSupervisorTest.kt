package dev.khronos31.mirakc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Px4DaemonSupervisorTest {
    @Test
    fun upstreamEndpointsFitAndroidUnixSocketPathForEverySupportedModelAndInstance() {
        val runtimeRoot = File("/data/user/0/dev.khronos31.mirakc/files/p4")
        var pathIndex = 1
        val identities = Px4DeviceModel.entries.flatMap { model ->
            model.productIds.flatMap { productId ->
                val serial = "%015d".format(productId + 1)
                if (model.bridgeCount == 2) {
                    val base = "%014d".format(productId + 1)
                    listOf(
                        Px4DeviceIdentity(px4TestDeviceName("/dev/bus/usb/001/%03d".format(pathIndex++)), "${base}1", productId),
                        Px4DeviceIdentity(px4TestDeviceName("/dev/bus/usb/001/%03d".format(pathIndex++)), "${base}2", productId)
                    )
                } else {
                    listOf(Px4DeviceIdentity(
                        px4TestDeviceName("/dev/bus/usb/001/%03d".format(pathIndex++)), serial, productId
                    ))
                }
            }
        }
        val plan = Px4DeviceSelector.plan(identities)

        assertTrue("all enclosures should be accepted", plan.rejections.isEmpty())
        assertEquals(Px4DeviceModel.entries.sumOf { it.productIds.size }, plan.enclosures.size)
        assertEquals(
            plan.enclosures.size,
            plan.enclosures.map { it.instanceToken }.toSet().size
        )
        val endpointPaths = mutableListOf<String>()
        plan.enclosures.forEach { enclosure ->
            val ownerRuntime = Px4RuntimeLayout.runtimeDirectoryForEnclosure(
                runtimeRoot, enclosure.instanceToken
            )
            listOf(
                Px4RuntimeLayout.CONTROL_ENDPOINT, // px4d, receiver control, readiness, and PC/SC card client
                Px4RuntimeLayout.STREAM_ENDPOINT // px4-ts stream client
            ).forEach { endpointName ->
                val path = Px4RuntimeLayout.endpoint(ownerRuntime, enclosure.instanceToken, endpointName)
                    .absolutePath
                assertEquals(
                    File(runtimeRoot, "px4-userland/v1/${enclosure.instanceToken}/$endpointName").absolutePath,
                    path
                )
                val pathBytesIncludingNul = path.toByteArray(Charsets.UTF_8).size + 1
                assertTrue(
                    "$endpointName path for ${enclosure.model} uses $pathBytesIncludingNul bytes: $path",
                    pathBytesIncludingNul <= UNIX_SOCKET_PATH_CAPACITY
                )
                endpointPaths += path
            }
        }
        assertEquals(endpointPaths.size, endpointPaths.toSet().size)
        assertTrue(plan.enclosures.all { it.instanceToken.length <= 80 })
    }

    @Test
    fun detachInvalidatesOldOwnerAndDrainsItBeforeSameIdentityCanStartAgain() {
        val device = Px4DeviceIdentity(px4TestDeviceName("usb-path-reused"), "123456789012345", Px4DeviceSelector.M1UR_PRODUCT_ID)
        val identities = listOf(device)
        val events = mutableListOf<String>()
        val runners = mutableListOf<FakeRunner>()
        val supervisor = Px4DaemonSupervisor(
            executable = { File("/unused/px4d") },
            firmware = { File("/unused/firmware") },
            identities = { identities },
            openDevice = { error("test owner must not open Android USB") },
            runtimeDir = File("/tmp/px4-owner-detach-test"),
            onStateChanged = {},
            onDaemonFailure = {},
            testOwnerFactory = { enclosure, _ ->
                FakeRunner(runners.size, enclosure, events).also(runners::add)
            }
        )

        assertEquals(1, supervisor.startAllOrGet().size)
        val detachedOwner = runners.single()
        supervisor.invalidateDetachedDevice(device.deviceName)

        assertTrue(detachedOwner.invalidated)
        assertFalse(detachedOwner.stopped)
        assertEquals(1, supervisor.startAllOrGet().size)

        assertEquals(2, runners.size)
        assertTrue(detachedOwner.stopped)
        assertEquals(listOf("start-0", "invalidate-0", "stop-0", "start-1"), events)
        supervisor.stop()
    }

    @Test
    fun allUpstreamProfilesRunAsSeparateOwnersAndDetachIsolatesOnlyMatchingEnclosure() {
        val identities = Px4DeviceModel.entries.flatMapIndexed { index, model ->
            val base = "%014d".format(index + 1)
            if (model.bridgeCount == 2) {
                listOf(
                    Px4DeviceIdentity(px4TestDeviceName("${model.adapterArgument}-1"), "${base}1", model.productIds.first()),
                    Px4DeviceIdentity(px4TestDeviceName("${model.adapterArgument}-2"), "${base}2", model.productIds.first())
                )
            } else {
                listOf(Px4DeviceIdentity(
                    px4TestDeviceName(model.adapterArgument),
                    "%015d".format(index + 100),
                    model.productIds.first()
                ))
            }
        }
        val events = mutableListOf<String>()
        val runners = mutableListOf<FakeRunner>()
        val supervisor = Px4DaemonSupervisor(
            executable = { File("/unused/px4d") },
            firmware = { File("/unused/firmware") },
            identities = { identities },
            openDevice = { error("fake owners must not open Android USB") },
            runtimeDir = File("/tmp/px4-all-profile-owner-test"),
            onStateChanged = {},
            onDaemonFailure = {},
            testOwnerFactory = { enclosure, _ ->
                FakeRunner(runners.size, enclosure, events).also(runners::add)
            }
        )

        val generations = supervisor.startAllOrGet()

        assertEquals(Px4DeviceModel.entries.size, generations.size)
        assertEquals(Px4DeviceModel.entries.size, runners.size)
        assertEquals(Px4DeviceModel.entries.toSet(), generations.map { it.model }.toSet())
        generations.forEach { generation ->
            assertEquals(generation.model.receiverCount, generation.tuners.size)
        }
        val target = runners.single { it.enclosure.model == Px4DeviceModel.MLT8PE3 }
        val otherOwners = runners.filter { it !== target }
        val targetUsbPath = target.enclosure.first.deviceName

        supervisor.invalidateDetachedDevice(targetUsbPath)

        assertTrue(target.invalidated)
        assertTrue(otherOwners.none { it.invalidated || it.stopped })
        assertEquals(Px4DeviceModel.entries.size, supervisor.startAllOrGet().size)
        assertTrue(target.stopped)
        assertTrue(otherOwners.none { it.stopped })
        assertEquals(Px4DeviceModel.entries.size + 1, runners.size)
        supervisor.stop()
    }

    @Test
    fun sameModelAndSerialKeepPathOwnedFdAndSurvivorAcrossDetachReorderAndReconnect() {
        val first = Px4DeviceIdentity(
            px4TestDeviceName("/dev/bus/usb/001/101"), "123456789012345", Px4DeviceSelector.M1UR_PRODUCT_ID
        )
        val second = first.copy(deviceName = px4TestDeviceName("/dev/bus/usb/001/102"))
        val reconnected = first.copy(deviceName = px4TestDeviceName("/dev/bus/usb/001/103"))
        var identities = listOf(first, second)
        val fdByPath = mapOf(first.deviceName to 41, second.deviceName to 42, reconnected.deviceName to 43)
        val openedPaths = mutableListOf<String>()
        val closedPaths = mutableListOf<String>()
        val runners = mutableListOf<FakeRunner>()
        val supervisor = Px4DaemonSupervisor(
            executable = { File("/unused/px4d") },
            firmware = { File("/unused/firmware") },
            identities = { identities },
            openDevice = { identity ->
                openedPaths += identity.deviceName
                Px4UsbHandle(fdByPath.getValue(identity.deviceName)) { closedPaths += identity.deviceName }
            },
            runtimeDir = File("/tmp/px4-same-serial-owners"),
            onStateChanged = {},
            onDaemonFailure = {},
            testOwnerFactory = { enclosure, ownerOpenDevice ->
                val handles = enclosure.devices.map(ownerOpenDevice)
                FakeRunner(runners.size, enclosure, mutableListOf(), handles).also(runners::add)
            }
        )

        val initial = supervisor.startAllOrGet()
        assertEquals(2, initial.size)
        assertEquals(setOf(first.deviceName, second.deviceName), runners.map { it.enclosure.first.deviceName }.toSet())
        assertEquals(listOf(first.deviceName, second.deviceName), openedPaths)
        assertEquals(setOf(41, 42), runners.map { it.usbHandles.single().fd }.toSet())
        val survivor = runners.single { it.enclosure.first.deviceName == second.deviceName }
        val survivorGeneration = initial.single { it.instanceToken == survivor.enclosure.instanceToken }
        val survivorFd = survivor.usbHandles.single().fd
        identities = listOf(second, first)
        assertEquals(initial, supervisor.startAllOrGet())
        assertEquals(2, runners.size)

        identities = listOf(second)
        supervisor.invalidateDetachedDevice(first.deviceName)
        val afterDetach = supervisor.startAllOrGet()
        assertEquals(1, afterDetach.size)
        assertTrue(runners.first { it.enclosure.first.deviceName == first.deviceName }.stopped)
        assertTrue(runners.none { it.enclosure.first.deviceName == second.deviceName && it.stopped })
        assertEquals(survivorGeneration, afterDetach.single())
        assertEquals(survivorFd, survivor.usbHandles.single().fd)
        assertEquals(listOf(first.deviceName), closedPaths)

        identities = listOf(reconnected, second)
        val afterReconnect = supervisor.startAllOrGet()
        assertEquals(2, afterReconnect.size)
        assertEquals(survivorGeneration, afterReconnect.single { it.instanceToken == survivor.enclosure.instanceToken })
        assertTrue(afterReconnect.any { it.instanceToken != survivor.enclosure.instanceToken })
        assertEquals(listOf(first.deviceName, second.deviceName, reconnected.deviceName), openedPaths)
        supervisor.stop()
    }

    @Test
    fun permissionPendingIdentityCanBeReconciledWhenItArrives() {
        var identities = emptyList<Px4DeviceIdentity>()
        val runners = mutableListOf<FakeRunner>()
        val supervisor = Px4DaemonSupervisor(
            executable = { File("/unused/px4d") },
            firmware = { File("/unused/firmware") },
            identities = { identities },
            openDevice = { error("fake owner must not open Android USB") },
            runtimeDir = File("/tmp/px4-permission-pending-test"),
            onStateChanged = {},
            onDaemonFailure = {},
            testOwnerFactory = { enclosure, _ ->
                FakeRunner(runners.size, enclosure, mutableListOf()).also(runners::add)
            }
        )

        assertTrue(supervisor.startAllOrGet().isEmpty())
        identities = listOf(Px4DeviceIdentity(
            px4TestDeviceName("/dev/bus/usb/001/104"), "123456789012345", Px4DeviceSelector.M1UR_PRODUCT_ID
        ))
        assertEquals(1, supervisor.startAllOrGet().size)
        assertEquals(1, runners.size)
        supervisor.stop()
    }

    private class FakeRunner(
        private val id: Int,
        val enclosure: Px4Enclosure,
        private val events: MutableList<String>,
        val usbHandles: List<Px4UsbHandle> = emptyList()
    ) : Px4EnclosureRunner {
        var invalidated = false
            private set
        var stopped = false
            private set

        override fun startOrGet(): Px4Generation? {
            events += "start-$id"
            return Px4Generation(
                enclosure.serial,
                enclosure.model,
                File("/tmp/${enclosure.instanceToken}"),
                enclosure.instanceToken,
                Px4DeviceSelector.tunersFor(enclosure)
            )
        }

        override fun invalidateForDetach() {
            invalidated = true
            events += "invalidate-$id"
        }

        override fun stop() {
            stopped = true
            events += "stop-$id"
            usbHandles.forEach { it.close() }
        }

        override fun status(): String = if (stopped) "stopped" else "running"
    }

    private companion object {
        // sockaddr_un.sun_path includes the terminating NUL byte.
        const val UNIX_SOCKET_PATH_CAPACITY = 108
    }
}
