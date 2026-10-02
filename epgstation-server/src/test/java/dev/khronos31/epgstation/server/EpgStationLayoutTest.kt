package dev.khronos31.epgstation.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpgStationLayoutTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyInternalRecordingsAndDatabaseSurviveMigrationAndPayloadUpdates() {
        val filesDir = temporaryFolder.newFolder("files")
        val root = EpgStationLayout.persistentRoot(filesDir)
        val database = File(root, "data/database.db").apply {
            parentFile!!.mkdirs()
            writeText("synthetic sqlite database with reservation rows")
        }
        val recording = File(root, "recorded/program.ts").apply {
            parentFile!!.mkdirs()
            writeText("synthetic recording")
        }
        val thumbnail = File(root, "thumbnail/program.jpg").apply {
            parentFile!!.mkdirs()
            writeText("synthetic thumbnail")
        }
        val savedConfig = File(root, "config/config.yml").apply {
            parentFile!!.mkdirs()
            writeText("synthetic persisted EPGStation config")
        }
        val savedLogConfig = File(root, "config/operatorLogConfig.yml").apply {
            writeText("synthetic persisted log config")
        }
        val legacyPayload = File(root, "dist/index.js").apply {
            parentFile!!.mkdirs()
            writeText("legacy payload")
        }
        File(root, "payload.version").writeText("legacy-version")

        val first = prepare(filesDir, "payload-v1")

        assertEquals("payload-v1", File(first.payload, "payload.version").readText())
        assertTrue(File(first.payload, "dist/index.js").isFile)
        assertTrue(File(first.payload, "data").canonicalFile == first.data.canonicalFile)
        assertEquals("synthetic sqlite database with reservation rows", database.readText())
        assertEquals("synthetic recording", recording.readText())
        assertEquals("synthetic thumbnail", thumbnail.readText())
        assertEquals("synthetic persisted EPGStation config", File(first.payload, "config/config.yml").readText())
        assertEquals("synthetic persisted log config", File(first.payload, "config/operatorLogConfig.yml").readText())
        assertEquals("legacy payload", legacyPayload.readText())

        prepare(filesDir, "payload-v2")

        assertEquals("payload-v2", File(first.payload, "payload.version").readText())
        assertEquals("synthetic sqlite database with reservation rows", database.readText())
        assertEquals("synthetic recording", recording.readText())
        assertEquals("synthetic thumbnail", thumbnail.readText())
        assertEquals("synthetic persisted EPGStation config", savedConfig.readText())
        assertEquals("synthetic persisted log config", savedLogConfig.readText())
        assertEquals("synthetic persisted EPGStation config", File(first.payload, "config/config.yml").readText())
        assertFalse(File(root, ".payload-staging").exists())
        assertFalse(File(root, ".payload-backup").exists())
    }

    @Test
    fun externalRecordingAndReservationsSurvivePayloadMigration() {
        val filesDir = temporaryFolder.newFolder("files-external")
        val externalVolume = temporaryFolder.newFolder("usb")
        val externalRecording = File(externalVolume, "recorded/program.ts").apply {
            parentFile!!.mkdirs()
            writeText("synthetic external recording")
        }
        val database = File(EpgStationLayout.persistentRoot(filesDir), "data/database.db").apply {
            parentFile!!.mkdirs()
            writeText("synthetic sqlite database with scheduled reservations")
        }

        prepare(filesDir, "payload-v1")
        prepare(filesDir, "payload-v2")

        assertEquals("synthetic external recording", externalRecording.readText())
        assertEquals("synthetic sqlite database with scheduled reservations", database.readText())
    }

    @Test
    fun failedPayloadStagingLeavesExistingPayloadAndPersistentDataUntouched() {
        val filesDir = temporaryFolder.newFolder("files-failure")
        val first = prepare(filesDir, "payload-v1")
        val database = File(first.data, "database.db").apply { writeText("synthetic reservation database") }
        val recording = File(first.root, "recorded/program.ts").apply {
            parentFile!!.mkdirs()
            writeText("synthetic recording")
        }

        try {
            EpgStationLayout.prepare(
                filesDir = filesDir,
                version = "payload-v2",
                installPayload = { target ->
                    installPayload(target, "partial-v2")
                    throw IOException("synthetic asset extraction failure")
                },
                createSymbolicLink = ::createDataLink,
                readSymbolicLink = ::readDataLink
            )
            throw AssertionError("Expected staged extraction to fail")
        } catch (error: IOException) {
            assertEquals("synthetic asset extraction failure", error.message)
        }

        assertEquals("payload-v1", File(first.payload, "payload.version").readText())
        assertEquals("synthetic reservation database", database.readText())
        assertEquals("synthetic recording", recording.readText())
        assertTrue(File(first.payload, "data").canonicalFile == first.data.canonicalFile)
    }

    private fun prepare(filesDir: File, version: String): EpgStationDirectories =
        EpgStationLayout.prepare(
            filesDir = filesDir,
            version = version,
            installPayload = { installPayload(it, version) },
            createSymbolicLink = ::createDataLink,
            readSymbolicLink = ::readDataLink
        )

    private fun installPayload(target: File, version: String) {
        File(target, "dist/index.js").apply {
            parentFile!!.mkdirs()
            writeText("synthetic $version")
        }
        File(target, "payload.version").writeText(version)
    }

    private fun createDataLink(target: File, link: File) {
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    private fun readDataLink(link: File): String? =
        runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrNull()
}
