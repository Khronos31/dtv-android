package dev.khronos31.mirakc

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object UserlandControl {
    data class InvocationResult(val exitCode: Int?, val output: String)

    fun invoke(
        executable: String,
        arguments: List<String>,
        usbFd: Int,
        timeoutMs: Long = 30_000
    ): InvocationResult {
        val process = NativeUsbProcess.startCommand(executable, arguments, usbFd)
        val output = CompletableFuture<ByteArray>()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(process.output).use { input ->
                    val captured = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var overflow = false
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (captured.size() + count <= MAX_DIAGNOSTIC_BYTES) captured.write(buffer, 0, count)
                        else overflow = true
                    }
                    if (overflow) throw IOException("stage diagnostic exceeded limit")
                    output.complete(captured.toByteArray())
                }
            } catch (error: Exception) {
                output.completeExceptionally(error)
            }
        }, "userland-stage-${process.pid}").apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (System.nanoTime() < deadline) {
                when (val result = NativeUsbProcess.pollCommand(process.pid)) {
                    NativeUsbProcess.PollResult.ALIVE -> Thread.sleep(POLL_INTERVAL_MS)
                    is NativeUsbProcess.PollResult.EXITED -> {
                        val remaining = (deadline - System.nanoTime()).coerceAtLeast(1)
                        val bytes = output.get(remaining, TimeUnit.NANOSECONDS)
                        val text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                        return InvocationResult(result.code, text)
                    }
                    NativeUsbProcess.PollResult.ERROR -> throw IOException("unable to poll stage command")
                }
            }
            throw IOException("stage command timed out")
        } catch (error: Exception) {
            NativeUsbProcess.stopCommand(process.pid)
            try { process.output.close() } catch (_: IOException) { }
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw if (error is IOException) error else IOException("stage command failed", error)
        }
    }

    fun status(
        executable: String,
        runtimeDir: String,
        instance: String,
        expectedProduct: String,
        timeoutMs: Long = 2_000,
        group: Boolean = false
    ): UserlandStatusInventory {
        val arguments = mutableListOf("--instance", instance, "--runtime-dir", runtimeDir)
        if (group) arguments += "--group"
        arguments += "status"
        val process = NativeUsbProcess.startCommand(
            executable,
            arguments
        )
        val output = CompletableFuture<ByteArray>()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseInputStream(process.output).use { input ->
                    val captured = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var overflow = false
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (captured.size() + count <= MAX_STATUS_BYTES) captured.write(buffer, 0, count)
                        else overflow = true
                    }
                    if (overflow) throw IOException("status output exceeded limit")
                    output.complete(captured.toByteArray())
                }
            } catch (error: Exception) {
                output.completeExceptionally(error)
            }
        }, "userland-status-${process.pid}").apply { isDaemon = true; start() }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (System.nanoTime() < deadline) {
                when (val result = NativeUsbProcess.pollCommand(process.pid)) {
                    NativeUsbProcess.PollResult.ALIVE -> Thread.sleep(POLL_INTERVAL_MS)
                    is NativeUsbProcess.PollResult.EXITED -> {
                        if (result.code != 0) throw IOException("status command exited ${result.code ?: "abnormally"}")
                        val remaining = (deadline - System.nanoTime()).coerceAtLeast(1)
                        val bytes = output.get(remaining, TimeUnit.NANOSECONDS)
                        val text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString()
                        return UserlandStatusInventory.parse(text, expectedProduct, instance)
                    }
                    NativeUsbProcess.PollResult.ERROR -> throw IOException("unable to poll status command")
                }
            }
            throw IOException("status command timed out")
        } catch (error: Exception) {
            NativeUsbProcess.stopCommand(process.pid)
            try { process.output.close() } catch (_: IOException) { }
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw if (error is IOException) error else IOException("status command failed", error)
        }
    }

    private const val MAX_STATUS_BYTES = 1024 * 1024
    private const val MAX_DIAGNOSTIC_BYTES = 64 * 1024
    private const val POLL_INTERVAL_MS = 20L
}
