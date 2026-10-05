package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPermissionTargetsTest {
    private data class Device(val id: String)

    @Test
    fun setupPermissionSequenceIncludesReaderAfterTunerWithoutCountingItAsTuner() {
        val targets = combineUsbPermissionTargets(
            tunerTargets = listOf(Device("siano")),
            readerTargets = listOf(Device("ccid"), Device("siano")),
            deviceId = Device::id
        )
        assertEquals(listOf("ccid", "siano"), targets.map(Device::id))

        val continuation = ScanPermissionContinuation()
        assertEquals(
            ScanPermissionContinuation.Decision.RequestPermission("ccid"),
            continuation.begin(301L, targets.map(Device::id))
        )
        assertEquals(
            ScanPermissionContinuation.Decision.RequestPermission("siano"),
            continuation.onPermissionResult(301L, granted = true, stillMissingDeviceIds = listOf("siano"))
        )
        assertEquals(
            ScanPermissionContinuation.Decision.StartScan,
            continuation.onPermissionResult(301L, granted = true, stillMissingDeviceIds = emptyList())
        )
        assertTrue(continuation.isPending.not())
    }
}
