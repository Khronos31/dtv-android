package dev.khronos31.mirakc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Px4DaemonSupervisorTest {
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

    private class FakeRunner(
        private val id: Int,
        private val enclosure: Px4Enclosure,
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
}
