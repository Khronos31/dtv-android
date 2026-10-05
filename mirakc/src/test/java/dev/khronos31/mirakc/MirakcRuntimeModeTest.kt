package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MirakcRuntimeModeTest {
    @Test
    fun scanAndPublicRuntimesHaveSeparateCachesAndListeners() {
        val root = File("/data/user/0/dev.khronos31.mirakc/files")
        val scan = mirakcRuntimeLayout(root, MirakcRuntimeMode.SCAN_ONLY)
        val public = mirakcRuntimeLayout(root, MirakcRuntimeMode.PUBLIC)

        assertNotEquals(scan.runtimeDirectory, public.runtimeDirectory)
        assertNotEquals(scan.cacheDirectory, public.cacheDirectory)
        assertNotEquals(scan.recordingDirectory, public.recordingDirectory)
        assertEquals("127.0.0.1:40773", scan.listenAddress)
        assertEquals("0.0.0.0:40772", public.listenAddress)
    }

    @Test
    fun scanModeDisablesEveryScheduledJobButRetainsCommandsForManualScan() {
        val jobs = renderMirakcJobCommands("/native/libmirakc-arib.so", enabled = false)

        assertTrue(jobs.contains("scan-services:\n    command: /system/bin/timeout 30 /native/libmirakc-arib.so scan-services"))
        assertTrue(jobs.contains("sync-clocks:\n    command:"))
        assertTrue(jobs.contains("update-schedules:\n    command:"))
        assertEquals(3, Regex("disabled: true").findAll(jobs).count())
        assertFalse(jobs.contains("disabled: false"))
    }

    @Test
    fun publicModeKeepsExistingJobDefaultsEnabled() {
        val jobs = renderMirakcJobCommands("/native/libmirakc-arib.so")

        assertEquals(3, Regex("disabled: false").findAll(jobs).count())
        assertFalse(jobs.contains("disabled: true"))
    }
}
