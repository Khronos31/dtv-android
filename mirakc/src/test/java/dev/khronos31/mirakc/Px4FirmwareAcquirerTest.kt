package dev.khronos31.mirakc

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class Px4FirmwareAcquirerTest {
    @Test
    fun rejectsInvalidSegmentMetadataWithoutOverflowOrOutOfBoundsReads() {
        val badType = syntheticSys(partitionCount = 1, type = 2, length = 2_169)
        assertThrows(IOException::class.java) { Px4FirmwareImageExtractor.locateCode(badType) }

        val shortOutput = syntheticSys(partitionCount = 1, type = 1, length = 2_168)
        assertThrows(IOException::class.java) { Px4FirmwareImageExtractor.locateCode(shortOutput) }

        val oversizedOutput = syntheticSys(partitionCount = 1, type = 1, length = -1)
        assertThrows(IOException::class.java) { Px4FirmwareImageExtractor.locateCode(oversizedOutput) }

        val truncatedTable = ByteArray(PARTITION_OFFSET + 1).apply { this[PARTITION_OFFSET] = 1 }
        assertThrows(IOException::class.java) { Px4FirmwareImageExtractor.locateCode(truncatedTable) }
    }

    @Test
    fun realPlexZipProducesExactStableReferenceFirmwareAndCorruptionPreservesCache() {
        val archivePath = System.getenv("MIRAKC_PLEX_DRIVER_ZIP")
        val referencePath = System.getenv("MIRAKC_PX4_REFERENCE_FIRMWARE")
        assumeTrue("set MIRAKC_PLEX_DRIVER_ZIP and MIRAKC_PX4_REFERENCE_FIRMWARE for the real driver regression",
            !archivePath.isNullOrBlank() && File(archivePath).isFile &&
                !referencePath.isNullOrBlank() && File(referencePath).isFile)

        val archive = File(archivePath!!).readBytes()
        val reference = File(referencePath!!).readBytes()
        val extracted = Px4FirmwareImageExtractor.extractArchive(archive)
        assertEquals(Px4FirmwareImageExtractor.FIRMWARE_SIZE, extracted.size)
        assertArrayEquals(reference, extracted)

        val workDirectory = Files.createTempDirectory("px4-firmware-regression-").toFile()
        try {
            val destination = File(workDirectory, Px4FirmwareImageExtractor.OUTPUT_NAME)
            destination.writeBytes(reference)
            val acquirer = Px4FirmwareAcquirer(
                destinationDirectory = { workDirectory },
                renameFile = { source, target ->
                    if (!source.renameTo(target)) throw IOException("atomic test rename failed")
                },
                logInfo = { _, _ -> }
            )

            destination.writeBytes(ByteArray(4))
            val installed = acquirer.installArchive(workDirectory, archive)
            assertEquals(destination, installed)
            assertTrue(Px4FirmwareImageExtractor.isValid(destination))
            assertArrayEquals(reference, destination.readBytes())
            assertEquals(destination, acquirer.ensure())
            assertArrayEquals(reference, destination.readBytes())

            val corruptedArchive = archive.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            assertThrows(IOException::class.java) { acquirer.installArchive(workDirectory, corruptedArchive) }
            assertArrayEquals(reference, destination.readBytes())

            val sys = Px4FirmwareImageExtractor.extractSys(archive)
            val corruptedSys = sys.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            assertThrows(IOException::class.java) { acquirer.installSys(workDirectory, corruptedSys) }
            assertArrayEquals(reference, destination.readBytes())

            val corruptedOutput = extracted.copyOf().apply {
                this[this.lastIndex] = (this[this.lastIndex].toInt() xor 1).toByte()
            }
            assertThrows(IOException::class.java) { acquirer.installCandidate(workDirectory, corruptedOutput) }
            assertArrayEquals(reference, destination.readBytes())
        } finally {
            workDirectory.deleteRecursively()
        }
    }

    private fun syntheticSys(partitionCount: Int, type: Int, length: Int): ByteArray =
        ByteArray(Px4FirmwareImageExtractor.SYS_SIZE).apply {
            this[PARTITION_OFFSET] = partitionCount.toByte()
            writeLe32(SEGMENT_TABLE_OFFSET, type)
            writeLe32(SEGMENT_TABLE_OFFSET + 4, length)
        }

    private fun ByteArray.writeLe32(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private companion object {
        const val PARTITION_OFFSET = 0x28666
        const val SEGMENT_TABLE_OFFSET = 0x29050
    }
}
