package dev.khronos31.mirakc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrScanStateRecoveryTest {
    @Test
    fun queuedAndRunningPersistedStatesBecomeInterruptedAndMarkersAreRemoved() {
        listOf(
            GrScanStatus.queued(401L),
            GrScanStatus(
                state = GrScanStatus.State.RUNNING,
                completed = 7,
                total = 50,
                currentChannel = 20,
                foundChannels = listOf(13, 16),
                failedChannels = 1,
                scanId = 402L,
                errorCode = null
            )
        ).forEach { old ->
            val directory = Files.createTempDirectory("gr-scan-recovery-").toFile()
            try {
                val stateFile = directory.resolve(".gr-scan-state")
                stateFile.writeText(old.toFileContents())
                val preparedChannels = directory.resolve("prepared-channels.json")
                preparedChannels.writeText("previous-good-configuration")
                val requestMarker = directory.resolve(".acceptance-gr-scan").apply { writeText("request") }
                val cancelMarker = directory.resolve(".acceptance-cancel-gr-scan").apply { writeText("cancel") }

                val recovered = recoverPersistedGrScanState(stateFile, 4_096L)

                assertEquals(GrScanStatus.State.INTERRUPTED, recovered?.state)
                assertEquals(old.scanId, recovered?.scanId)
                assertEquals(old.completed, recovered?.completed)
                assertEquals(old.foundChannels, recovered?.foundChannels)
                assertEquals(old.failedChannels, recovered?.failedChannels)
                assertNull(recovered?.currentChannel)
                assertEquals("PROCESS_RESTARTED", recovered?.errorCode)
                assertEquals(recovered, GrScanStatus.parse(stateFile.readText()))
                assertFalse(requestMarker.exists())
                assertFalse(cancelMarker.exists())
                assertEquals("previous-good-configuration", preparedChannels.readText())
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun completedAndMalformedFilesAreNotRewritten() {
        val directory = Files.createTempDirectory("gr-scan-recovery-terminal-").toFile()
        try {
            val stateFile = directory.resolve(".gr-scan-state")
            stateFile.writeText(GrScanStatus.interrupted(410L).toFileContents())
            assertNull(recoverPersistedGrScanState(stateFile, 4_096L))
            assertEquals(GrScanStatus.State.INTERRUPTED, GrScanStatus.parse(stateFile.readText())?.state)

            stateFile.writeText("not a scan state")
            assertNull(recoverPersistedGrScanState(stateFile, 4_096L))
            assertEquals("not a scan state", stateFile.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
