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
        val sharedSerial = "123456789012345"
        val identities = listOf(
            Px4DeviceIdentity("q3-1", "123456789012341", Px4DeviceSelector.Q3U4_PRODUCT_ID),
            Px4DeviceIdentity("q3-2", "123456789012342", Px4DeviceSelector.Q3U4_PRODUCT_ID),
            Px4DeviceIdentity("mlt", "987654321098765", 0x024e),
            Px4DeviceIdentity("m1ur", sharedSerial, Px4DeviceSelector.M1UR_PRODUCT_ID),
            Px4DeviceIdentity("s1ur", sharedSerial, Px4DeviceSelector.S1UR_PRODUCT_ID)
        )
        val plan = Px4DeviceSelector.plan(identities)

        assertTrue("all enclosures should be accepted", plan.rejections.isEmpty())
        assertEquals(4, plan.enclosures.size)
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
                Px4RuntimeLayout.CONTROL_ENDPOINT, // px4d, readiness, and PC/SC card client
                Px4RuntimeLayout.STREAM_ENDPOINT // px4-ts stream client
            ).forEach { endpointName ->
                val path = Px4RuntimeLayout.endpoint(ownerRuntime, enclosure.instanceToken, endpointName)
                    .absolutePath
                val pathBytesIncludingNul = path.toByteArray(Charsets.UTF_8).size + 1
                assertTrue(
                    "$endpointName path for ${enclosure.model} uses $pathBytesIncludingNul bytes: $path",
                    pathBytesIncludingNul <= UNIX_SOCKET_PATH_CAPACITY
                )
                endpointPaths += path
            }
        }
        assertEquals(endpointPaths.size, endpointPaths.toSet().size)
        assertTrue(
            plan.enclosures.single { it.model == Px4DeviceModel.M1UR }.instanceToken !=
                plan.enclosures.single { it.model == Px4DeviceModel.S1UR }.instanceToken
        )
    }

    @Test
    fun detachInvalidatesOldOwnerAndDrainsItBeforeSameIdentityCanStartAgain() {
        val device = Px4DeviceIdentity("usb-path-reused", "123456789012345", Px4DeviceSelector.M1UR_PRODUCT_ID)
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
            testOwnerFactory = { enclosure ->
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
                    Px4DeviceIdentity("${model.adapterArgument}-1", "${base}1", model.productIds.first()),
                    Px4DeviceIdentity("${model.adapterArgument}-2", "${base}2", model.productIds.first())
                )
            } else {
                listOf(Px4DeviceIdentity(
                    model.adapterArgument,
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
            testOwnerFactory = { enclosure ->
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

    private class FakeRunner(
        private val id: Int,
        val enclosure: Px4Enclosure,
        private val events: MutableList<String>
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
        }

        override fun status(): String = if (stopped) "stopped" else "running"
    }

    private companion object {
        // sockaddr_un.sun_path includes the terminating NUL byte.
        const val UNIX_SOCKET_PATH_CAPACITY = 108
    }
}
