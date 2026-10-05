package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPermissionContinuationTest {
    @Test
    fun grantChainAutomaticallyStartsTheOriginalRequestedScan() {
        val permissions = ScanPermissionContinuation()

        assertEquals(
            ScanPermissionContinuation.Decision.RequestPermission("usb-siano"),
            permissions.begin(listOf("usb-siano", "usb-px4"))
        )
        assertEquals(
            ScanPermissionContinuation.Decision.RequestPermission("usb-px4"),
            permissions.onPermissionResult(true, listOf("usb-px4"))
        )
        assertEquals(
            ScanPermissionContinuation.Decision.StartScan,
            permissions.onPermissionResult(true, emptyList())
        )
        assertFalse(permissions.isPending)
    }

    @Test
    fun deniedPermissionAndCancelDoNotStartAScanLater() {
        val permissions = ScanPermissionContinuation()
        permissions.begin(listOf("usb-siano"))
        assertEquals(
            ScanPermissionContinuation.Decision.Denied,
            permissions.onPermissionResult(false, listOf("usb-siano"))
        )
        assertEquals(ScanPermissionContinuation.Decision.NotPending, permissions.onPermissionResult(true, emptyList()))

        permissions.begin(listOf("usb-px4"))
        assertEquals(ScanPermissionContinuation.Decision.Canceled, permissions.cancel())
        assertEquals(ScanPermissionContinuation.Decision.NotPending, permissions.processStopped())
        assertFalse(permissions.isPending)
    }

    @Test
    fun noMissingPermissionStartsImmediately() {
        val permissions = ScanPermissionContinuation()
        assertEquals(ScanPermissionContinuation.Decision.StartScan, permissions.begin(emptyList()))
        assertTrue(!permissions.isPending)
    }

    @Test
    fun denialForCanceledScanCannotCancelNextOperation() {
        val permissions = ScanPermissionContinuation()
        permissions.begin(70L, listOf("usb-A"))
        assertEquals(ScanPermissionContinuation.Decision.Canceled, permissions.cancel(70L))
        permissions.begin(71L, listOf("usb-B"))

        assertEquals(
            ScanPermissionContinuation.Decision.NotPending,
            permissions.onPermissionResult(70L, granted = false, stillMissingDeviceIds = listOf("usb-B"))
        )
        assertTrue(permissions.isPending)
        assertEquals(
            ScanPermissionContinuation.Decision.RequestPermission("usb-B"),
            permissions.onPermissionResult(71L, granted = true, stillMissingDeviceIds = listOf("usb-B"))
        )
    }
}
