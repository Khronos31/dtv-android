package dev.khronos31.mirakc

import android.system.Os
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/** Downloads and independently extracts the PX4 firmware without replacing a valid cache on failure. */
internal class Px4FirmwareAcquirer(
    private val destinationDirectory: () -> File?,
    private val renameFile: (File, File) -> Unit = { source, destination ->
        Os.rename(source.absolutePath, destination.absolutePath)
    },
    private val logInfo: (String, String) -> Unit = { tag, message -> Log.i(tag, message) }
) {
    fun ensure(): File = synchronized(processLock) {
        val directory = destinationDirectory()
            ?: throw IOException("external files directory unavailable")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("cannot create firmware directory")
        }
        val lockFile = File(directory, "${Px4FirmwareImageExtractor.OUTPUT_NAME}.lock")
        FileOutputStream(lockFile, true).use { lockStream ->
            lockStream.channel.lock().use { ensureLocked(directory) }
        }
    }

    private fun ensureLocked(directory: File): File {
        val destination = File(directory, Px4FirmwareImageExtractor.OUTPUT_NAME)
        if (Px4FirmwareImageExtractor.isValid(destination)) {
            logInfo(TAG, "PX4 firmware cache reused")
            return destination
        }

        logInfo(TAG, "PX4 firmware acquisition started")
        return installArchive(directory, downloadArchive())
    }

    /** Shared pipeline entry used by regression tests with the real vendor ZIP. */
    internal fun installArchive(directory: File, archive: ByteArray): File {
        val firmware = Px4FirmwareImageExtractor.extractArchive(archive)
        return installCandidate(directory, firmware)
    }

    /** Validates a standalone SYS input before extracting and atomically installing it. */
    internal fun installSys(directory: File, sys: ByteArray): File =
        installCandidate(directory, Px4FirmwareImageExtractor.extractFirmware(sys))

    /** Candidate validation happens before temp-file creation or replacement of the destination. */
    internal fun installCandidate(directory: File, candidate: ByteArray): File {
        Px4FirmwareImageExtractor.requireValidOutput(candidate)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("cannot create firmware directory")
        }
        val destination = File(directory, Px4FirmwareImageExtractor.OUTPUT_NAME)
        val temporary = File.createTempFile(".px4-firmware-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(candidate)
                output.fd.sync()
            }
            if (!Px4FirmwareImageExtractor.isValid(temporary)) {
                throw IOException("generated firmware checksum mismatch")
            }
            renameFile(temporary, destination)
            logInfo(TAG, "PX4 firmware atomically installed")
            return destination
        } finally {
            temporary.delete()
        }
    }

    private fun downloadArchive(): ByteArray {
        var url = URL(Px4FirmwareImageExtractor.ARCHIVE_URL)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_NS
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val remainingNs = deadline - System.nanoTime()
            if (remainingNs <= 0L) throw IOException("firmware download timeout")
            val remainingMs = (remainingNs / 1_000_000L).coerceAtLeast(1L)
            requireHttps(url)
            val connection = (url.openConnection() as? HttpURLConnection)
                ?: throw IOException("unsupported firmware URL")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = minOf(CONNECT_TIMEOUT_MS.toLong(), remainingMs).toInt()
                connection.readTimeout = minOf(READ_TIMEOUT_MS.toLong(), remainingMs).toInt()
                connection.setRequestProperty("User-Agent", "dtv-android-px4-firmware")
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectIndex == MAX_REDIRECTS) throw IOException("too many HTTPS redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("HTTPS redirect has no location")
                    url = URI(url.toString()).resolve(location).toURL()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("firmware download HTTP $code")
                }
                val length = connection.contentLengthLong
                if (length > Px4FirmwareImageExtractor.ARCHIVE_SIZE) {
                    throw IOException("firmware archive too large")
                }
                val archive = readBounded(connection.inputStream, Px4FirmwareImageExtractor.ARCHIVE_SIZE, deadline)
                if (archive.size.toLong() != Px4FirmwareImageExtractor.ARCHIVE_SIZE ||
                    sha256(archive) != Px4FirmwareImageExtractor.ARCHIVE_SHA256
                ) {
                    throw IOException("firmware archive checksum mismatch")
                }
                return archive
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("firmware redirect loop")
    }

    private fun requireHttps(url: URL) {
        if (url.protocol != "https" || url.userInfo != null ||
            (url.port != -1 && url.port != 443)
        ) {
            throw IOException("firmware redirect is not safe HTTPS")
        }
    }

    private fun readBounded(input: InputStream, maximum: Long, deadline: Long? = null): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximum, Int.MAX_VALUE.toLong()).toInt())
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (deadline != null && System.nanoTime() >= deadline) {
                throw IOException("firmware download timeout")
            }
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > maximum) {
                throw IOException("firmware payload exceeds bound")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val TOTAL_TIMEOUT_NS = 60_000_000_000L
        const val MAX_REDIRECTS = 3
        const val TAG = "Px4FirmwareAcquirer"
        val processLock = Any()
    }
}

/** PX-W3U4 BDA 1.0 profile, implemented from the documented fwtool extraction fields. */
internal object Px4FirmwareImageExtractor {
    const val ARCHIVE_URL = "https://plex-net.co.jp/plex/pxw3u4/pxw3u4_BDA_ver1x64.zip"
    const val ARCHIVE_SIZE = 213_410L
    const val SYS_ENTRY = "pxw3u4_BDA_ver1x64/PXW3U4.sys"
    const val SYS_SIZE = 189_440
    const val OUTPUT_NAME = "it930x-firmware.bin"
    const val FIRMWARE_SIZE = 2_169
    const val ARCHIVE_SHA256 = "bdf3b4eb84b69ccbacb4ba3df2f59c93c803ef6d3f61e7a90a531c22c301a200"
    const val SYS_SHA256 = "8c7b526e2c92f9b42440b55b99b309c33f2011f4e11de310acf0c1da58038722"
    const val FIRMWARE_SHA256 = "5213a5a38872661277a2cc1b2dfdfe88faf06f41205f460f3b51857f0568b484"

    private const val PARTITION_OFFSET = 0x28666
    private const val FIRMWARE_CODE_OFFSET = 0x287d0
    private const val SEGMENT_TABLE_OFFSET = 0x29050
    private const val SEGMENT_ALIGNMENT = 4
    private const val FIRMWARE_CRC32 = 0x0b41a994L
    fun extractArchive(archive: ByteArray): ByteArray {
        val sys = extractSys(archive)
        return extractFirmware(sys).also(::requireValidOutput)
    }

    /** Validates the pinned archive as well as exactly one pinned SYS member. */
    fun extractSys(archive: ByteArray): ByteArray {
        requireArchive(archive)
        var found = false
        var result: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != SYS_ENTRY) {
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory || found) {
                    throw IOException("duplicate or directory firmware SYS entry")
                }
                if (entry.size >= 0L && entry.size != SYS_SIZE.toLong()) {
                    throw IOException("firmware SYS declared size mismatch")
                }
                found = true
                val bytes = readBounded(zip, SYS_SIZE.toLong())
                if (zip.read() >= 0) throw IOException("firmware SYS exceeds expected size")
                zip.closeEntry()
                result = bytes
            }
        }
        val sys = result ?: throw IOException("firmware SYS entry missing")
        requireSys(sys)
        return sys
    }

    /** Extracts segment code and validates both fwtool CRC32 and the stable binary SHA-256. */
    fun extractFirmware(sys: ByteArray): ByteArray {
        requireSys(sys)
        val (offset, length) = locateCode(sys)
        val crc = CRC32().apply { update(sys, offset, length) }.value
        if (crc != FIRMWARE_CRC32) throw IOException("firmware code CRC32 mismatch")
        return sys.copyOfRange(offset, offset + length).also(::requireValidOutput)
    }

    /** Profile-layout validation is exposed internally for metadata corruption regressions. */
    internal fun locateCode(sys: ByteArray): Pair<Int, Int> {
        if (sys.size <= PARTITION_OFFSET) throw IOException("firmware partition count is missing")
        val partitionCount = sys[PARTITION_OFFSET].toInt() and 0xff
        if (partitionCount == 0) throw IOException("firmware has no segments")

        val tableEnd = SEGMENT_TABLE_OFFSET.toLong() +
            partitionCount.toLong() * SEGMENT_ALIGNMENT * 2L
        if (tableEnd > sys.size) throw IOException("firmware segment table exceeds SYS bounds")

        var codeLength = 0L
        repeat(partitionCount) { index ->
            val segmentOffset = SEGMENT_TABLE_OFFSET + index * SEGMENT_ALIGNMENT * 2
            val type = readLe32(sys, segmentOffset)
            if (type != 1L) throw IOException("unsupported firmware segment type")
            val segmentLength = readLe32(sys, segmentOffset + SEGMENT_ALIGNMENT)
            if (segmentLength <= 0L || segmentLength > FIRMWARE_SIZE.toLong() - codeLength) {
                throw IOException("firmware segment lengths exceed expected output bounds")
            }
            codeLength += segmentLength
        }
        if (codeLength != FIRMWARE_SIZE.toLong()) {
            throw IOException("firmware segment length mismatch")
        }
        val codeEnd = FIRMWARE_CODE_OFFSET.toLong() + codeLength
        if (codeEnd > sys.size) throw IOException("firmware code exceeds SYS bounds")
        return FIRMWARE_CODE_OFFSET to codeLength.toInt()
    }

    fun requireValidOutput(bytes: ByteArray) {
        if (bytes.size != FIRMWARE_SIZE || sha256(bytes) != FIRMWARE_SHA256) {
            throw IOException("generated firmware checksum mismatch")
        }
    }

    fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() != FIRMWARE_SIZE.toLong()) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toHex() == FIRMWARE_SHA256
        } catch (_: IOException) {
            false
        }
    }

    private fun requireArchive(archive: ByteArray) {
        if (archive.size.toLong() != ARCHIVE_SIZE || sha256(archive) != ARCHIVE_SHA256) {
            throw IOException("firmware archive checksum mismatch")
        }
    }

    private fun requireSys(sys: ByteArray) {
        if (sys.size != SYS_SIZE || sha256(sys) != SYS_SHA256) {
            throw IOException("firmware SYS checksum mismatch")
        }
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun readBounded(input: InputStream, maximum: Long): ByteArray {
        val output = ByteArrayOutputStream(maximum.toInt())
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > maximum) {
                throw IOException("firmware SYS exceeds expected size")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
