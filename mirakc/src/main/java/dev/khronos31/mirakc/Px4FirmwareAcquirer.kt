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
import java.util.zip.ZipInputStream

/** Downloads and generates the PX-Q3U4 firmware without modifying a valid cache. */
internal class Px4FirmwareAcquirer(
    private val destinationDirectory: () -> File?,
    private val fwtool: () -> File,
    private val openFwtoolAsset: (String) -> InputStream
) {
    fun ensure(): File = synchronized(processLock) {
        val directory = destinationDirectory()
            ?: throw IOException("external files directory unavailable")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("cannot create firmware directory")
        }
        val lockFile = File(directory, "$OUTPUT_NAME.lock")
        val result = FileOutputStream(lockFile, true).use { lockStream ->
            lockStream.channel.lock().use { ensureLocked(directory) }
        }
        return result
    }

    private fun ensureLocked(directory: File): File {
        val destination = File(directory, OUTPUT_NAME)
        if (isValid(destination, FIRMWARE_SIZE, FIRMWARE_SHA256)) {
            Log.i(TAG, "PX4 firmware cache reused")
            return destination
        }

        Log.i(TAG, "PX4 firmware acquisition started")
        val archive = downloadArchive()
        val sys = extractSys(archive)
        val tool = fwtool()
        if (!tool.isFile || !tool.canExecute()) throw IOException("fwtool unavailable")

        val toolDirectory = File(directory, ".px4-fwtool")
        if (!toolDirectory.exists() && !toolDirectory.mkdirs()) {
            throw IOException("cannot create fwtool directory")
        }
        installFwtoolAssets(toolDirectory)

        var sysTemp: File? = null
        var outputTemp: File? = null
        try {
            val inputTemp = File.createTempFile(".px4-sys-", ".tmp", directory)
            val candidateTemp = File.createTempFile(".px4-firmware-", ".tmp", directory)
            sysTemp = inputTemp
            outputTemp = candidateTemp
            writeAndSync(inputTemp, sys)
            runFwtool(tool, toolDirectory, inputTemp, candidateTemp)
            if (!isValid(candidateTemp, FIRMWARE_SIZE, FIRMWARE_SHA256)) {
                throw IOException("generated firmware checksum mismatch")
            }
            sync(candidateTemp)
            Os.rename(candidateTemp.absolutePath, destination.absolutePath)
            Log.i(TAG, "PX4 firmware atomically installed")
            return destination
        } finally {
            sysTemp?.delete()
            outputTemp?.delete()
        }
    }

    private fun downloadArchive(): ByteArray {
        var url = URL(ARCHIVE_URL)
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
                if (length > ARCHIVE_SIZE) throw IOException("firmware archive too large")
                val archive = readBounded(connection.inputStream, ARCHIVE_SIZE, deadline)
                if (archive.size.toLong() != ARCHIVE_SIZE || sha256(archive) != ARCHIVE_SHA256) {
                    throw IOException("firmware archive checksum mismatch")
                }
                return archive
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("firmware redirect loop")
    }

    private fun extractSys(archive: ByteArray): ByteArray {
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
                if (entry.size >= 0L && entry.size != SYS_SIZE) {
                    throw IOException("firmware SYS declared size mismatch")
                }
                found = true
                val bytes = readBounded(zip, SYS_SIZE)
                if (zip.read() >= 0) throw IOException("firmware SYS exceeds expected size")
                zip.closeEntry()
                result = bytes
            }
        }
        val sys = result ?: throw IOException("firmware SYS entry missing")
        if (sys.size.toLong() != SYS_SIZE || sha256(sys) != SYS_SHA256) {
            throw IOException("firmware SYS checksum mismatch")
        }
        return sys
    }

    private fun installFwtoolAssets(directory: File) {
        val target = File(directory, "fwinfo.tsv")
        if (isValid(target, FWINFO_SIZE, FWINFO_SHA256)) return
        val temporary = File.createTempFile(".fwinfo-", ".tmp", directory)
        try {
            openFwtoolAsset("fwinfo.tsv").use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (!isValid(temporary, FWINFO_SIZE, FWINFO_SHA256)) {
                throw IOException("fwinfo.tsv checksum mismatch")
            }
            sync(temporary)
            Os.rename(temporary.absolutePath, target.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun runFwtool(tool: File, directory: File, sys: File, output: File) {
        val process = ProcessBuilder(tool.absolutePath, sys.absolutePath, output.absolutePath)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val drain = Thread({
            process.inputStream.use { input ->
                val buffer = ByteArray(1024)
                var logged = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (logged < MAX_TOOL_LOG_BYTES) {
                        logged += count.coerceAtMost(MAX_TOOL_LOG_BYTES - logged)
                    }
                }
            }
        }, "px4-fwtool-output").also {
            it.isDaemon = true
            it.start()
        }
        try {
            if (!waitForBounded(process, FWTOOL_TIMEOUT_MS)) {
                terminateAndReap(process)
                throw IOException("fwtool timeout")
            }
            drain.join(1_000)
            if (process.exitValue() != 0) throw IOException("fwtool failed")
        } finally {
            if (!hasExited(process)) terminateAndReap(process)
            drain.interrupt()
        }
    }

    private fun hasExited(process: Process): Boolean = try {
        process.exitValue()
        true
    } catch (_: IllegalThreadStateException) {
        false
    }

    private fun terminateAndReap(process: Process) {
        process.destroy()
        if (!waitForBounded(process, FWTOOL_TERMINATION_TIMEOUT_MS)) {
            // Android's API 24 Process has no destroyForcibly(); destroy() is
            // the available kill request. Retry once, then report if bounded
            // reaping still cannot be observed.
            process.destroy()
            if (!waitForBounded(process, FWTOOL_TERMINATION_TIMEOUT_MS)) {
                Log.e(TAG, "fwtool did not exit after destroy")
            }
        }
    }

    private fun waitForBounded(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return try {
            process.exitValue()
            true
        } catch (_: IllegalThreadStateException) {
            false
        }
    }

    private fun isValid(file: File, expectedSize: Long, expectedSha: String): Boolean {
        if (!file.isFile || file.length() != expectedSize) return false
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
            digest.digest().toHex() == expectedSha
        } catch (_: IOException) {
            false
        }
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun sync(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun requireHttps(url: URL) {
        if (url.protocol != "https" || url.userInfo != null ||
            (url.port != -1 && url.port != 443)) {
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
        const val ARCHIVE_URL = "https://plex-net.co.jp/plex/pxw3u4/pxw3u4_BDA_ver1x64.zip"
        const val SYS_ENTRY = "pxw3u4_BDA_ver1x64/PXW3U4.sys"
        const val OUTPUT_NAME = "it930x-firmware.bin"
        const val ARCHIVE_SIZE = 213_410L
        const val SYS_SIZE = 189_440L
        const val FIRMWARE_SIZE = 2_169L
        const val FWINFO_SIZE = 4_166L
        const val ARCHIVE_SHA256 = "bdf3b4eb84b69ccbacb4ba3df2f59c93c803ef6d3f61e7a90a531c22c301a200"
        const val SYS_SHA256 = "8c7b526e2c92f9b42440b55b99b309c33f2011f4e11de310acf0c1da58038722"
        const val FIRMWARE_SHA256 = "5213a5a38872661277a2cc1b2dfdfe88faf06f41205f460f3b51857f0568b484"
        const val FWINFO_SHA256 = "74fe2d7ce115fc9f25dfbb928da40dcaec9c36accbfb10974c52648ec665ca40"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val TOTAL_TIMEOUT_NS = 60_000_000_000L
        const val FWTOOL_TIMEOUT_MS = 30_000L
        const val FWTOOL_TERMINATION_TIMEOUT_MS = 1_000L
        const val MAX_REDIRECTS = 3
        const val MAX_TOOL_LOG_BYTES = 16 * 1024
        const val TAG = "Px4FirmwareAcquirer"
        val processLock = Any()
    }
}
