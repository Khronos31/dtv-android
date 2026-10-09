package dev.khronos31.epgstation.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingStorageTest {
    private val pkg = "dev.khronos31.epgstation.server"
    private val uuid = "FAFB-B60F"

    @Test
    fun visibleAppDirectoryStaysAheadOfTheRawUsbMount() {
        val visible = File("/storage/$uuid/Android/data/$pkg/files")
        val volumeDirectory = File("/storage/$uuid")
        val candidates = RecordingStorage.removableRootCandidates(
            uuid = uuid,
            packageName = pkg,
            visibleAppDirs = listOf(visible),
            volumeDirectory = volumeDirectory,
            allFilesAccess = true
        )

        assertEquals(visible, candidates.first())
        assertEquals(File("/mnt/media_rw/$uuid/Android/data/$pkg/files"), candidates.last())
    }

    @Test
    fun hiddenUsbUsesMediaRwOnlyAfterAllFilesAccess() {
        val withoutAccess = RecordingStorage.removableRootCandidates(
            uuid = uuid,
            packageName = pkg,
            visibleAppDirs = emptyList(),
            volumeDirectory = null,
            allFilesAccess = false
        )
        val withAccess = RecordingStorage.removableRootCandidates(
            uuid = uuid,
            packageName = pkg,
            visibleAppDirs = emptyList(),
            volumeDirectory = null,
            allFilesAccess = true
        )

        assertEquals(emptyList<File>(), withoutAccess)
        assertEquals(listOf(File("/mnt/media_rw/$uuid/Android/data/$pkg/files")), withAccess)
    }

    @Test
    fun unsafeUuidProducesNoCandidate() {
        val candidates = RecordingStorage.removableRootCandidates(
            uuid = "../FAFB-B60F",
            packageName = pkg,
            visibleAppDirs = listOf(File("/storage/ignored")),
            volumeDirectory = File("/mnt/media_rw/ignored"),
            allFilesAccess = true
        )

        assertEquals(emptyList<File>(), candidates)
    }

    @Test
    fun firstUsableRootSkipsACandidateTheProbeRejects() {
        val blocked = File("/storage/$uuid/Android/data/$pkg/files")
        val raw = File("/mnt/media_rw/$uuid/Android/data/$pkg/files")

        assertEquals(raw, RecordingStorage.firstUsableRoot(listOf(blocked, raw)) { it == raw })
        assertNull(RecordingStorage.firstUsableRoot(listOf(blocked)) { _ -> false })
    }

    @Test
    fun mountedHiddenVolumeAsksForAllFilesAccessUntilTheProcessCanWrite() {
        assertEquals(
            RemovableAccess.NeedsAllFilesAccess,
            RecordingStorage.classifyRemovableAccess(
                mounted = true,
                writable = false,
                allFilesAccess = false,
                canRequestAllFilesAccess = true
            )
        )
        assertEquals(
            RemovableAccess.NeedsProcessRestart,
            RecordingStorage.classifyRemovableAccess(
                mounted = true,
                writable = false,
                allFilesAccess = true,
                canRequestAllFilesAccess = true
            )
        )
        assertEquals(
            RemovableAccess.Writable,
            RecordingStorage.classifyRemovableAccess(
                mounted = true,
                writable = true,
                allFilesAccess = true,
                canRequestAllFilesAccess = true
            )
        )
    }

    @Test
    fun unmountedOrLegacyVolumeStaysUnavailable() {
        assertEquals(
            RemovableAccess.Unavailable,
            RecordingStorage.classifyRemovableAccess(
                mounted = false,
                writable = false,
                allFilesAccess = false,
                canRequestAllFilesAccess = true
            )
        )
        assertEquals(
            RemovableAccess.Unavailable,
            RecordingStorage.classifyRemovableAccess(
                mounted = true,
                writable = false,
                allFilesAccess = false,
                canRequestAllFilesAccess = false
            )
        )
    }

    @Test
    fun hiddenVolumeRootFollowsTheSdkThatExposesMediaRw() {
        assertEquals(
            File("/mnt/media_rw/$uuid/Android/data/$pkg/files"),
            RecordingStorage.hiddenVolumeRoot(uuid, pkg, 30)
        )
        assertEquals(
            File("/storage/$uuid/Android/data/$pkg/files"),
            RecordingStorage.hiddenVolumeRoot(uuid, pkg, 29)
        )
    }
}
