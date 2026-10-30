package dev.khronos31.mirakc

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class SianoStreamOutcome(val wireValue: Int) {
    EXIT(1),
    SIGNAL(2),
    TIMEOUT(3),
    CANCEL(4),
    POLL_ERROR(5),
    BROKER_IO(6)
}

internal data class SianoStreamTerminal(val outcome: SianoStreamOutcome, val detail: Int) {
    fun adapterExitCode(): Int = when (outcome) {
        SianoStreamOutcome.EXIT -> detail
        SianoStreamOutcome.SIGNAL -> (128 + detail).coerceAtMost(255)
        else -> 1
    }
}

internal sealed class SianoChildStatus {
    data object ALIVE : SianoChildStatus()
    data class EXITED(val code: Int?, val signal: Int?) : SianoChildStatus()
    data object ERROR : SianoChildStatus()
}

/** Serializes native status polling with teardown, which can reap the same pid. */
internal class SianoTsProcessControl(
    private val pollProcess: () -> SianoChildStatus,
    private val stopProcess: () -> Unit
) {
    private val lock = Any()
    private var stopped = false

    fun poll(): SianoChildStatus = synchronized(lock) {
        if (stopped) SianoChildStatus.ERROR else try {
            pollProcess()
        } catch (_: Exception) {
            SianoChildStatus.ERROR
        }
    }

    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            try { stopProcess() } catch (_: Exception) { }
        }
    }
}

/** Owns the spawned TS child and both pipe descriptors, including partial setup. */
internal class SianoTsProcessResources(
    private val stopProcess: () -> Unit,
    private val closeOutput: () -> Unit,
    private val closeDiagnostics: () -> Unit
) {
    private val stopped = AtomicBoolean(false)

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { stopProcess() } catch (_: Exception) { }
        try { closeOutput() } catch (_: Exception) { }
        try { closeDiagnostics() } catch (_: Exception) { }
    }

    fun <T> setup(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        stop()
        throw error
    }
}

/** Writes the app-private framed TS stream and its single terminal outcome. */
internal class SianoTunerStreamSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val process: SianoTsProcessControl,
    private val closeOutput: () -> Unit,
    private val statusTimeoutMs: Long = STATUS_TIMEOUT_MS,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS
) {
    private enum class State { OPEN, ENDING, COMPLETE, FAILED, CANCELLED }
    private data class Snapshot(val state: State, val terminal: SianoStreamTerminal? = null)

    private val state = AtomicReference(Snapshot(State.OPEN))

    fun run(): SianoStreamTerminal {
        val data = ByteArray(MAX_DATA_BYTES)
        try {
            while (state.get().state == State.OPEN) {
                val count = input.read(data)
                if (count < 0) break
                if (count == 0) continue
                if (state.get().state != State.OPEN) return terminalOrCancel()
                writeData(data, count)
            }
        } catch (_: IOException) {
            return fail(SianoStreamTerminal(SianoStreamOutcome.BROKER_IO, 0))
        }
        val terminal = awaitChildExit()
        return writeEnd(terminal)
    }

    /** Cancellation never waits for a writer lock; closing the socket unblocks a pending write. */
    fun cancel() {
        while (true) {
            val current = state.get()
            if (current.state != State.OPEN) break
            if (state.compareAndSet(
                    current,
                    Snapshot(State.CANCELLED, SianoStreamTerminal(SianoStreamOutcome.CANCEL, 0))
                )) break
        }
        try { closeOutput() } catch (_: Exception) { }
    }

    private fun writeData(data: ByteArray, count: Int) {
        val header = header(DATA, count)
        output.write(header)
        output.write(data, 0, count)
    }

    private fun awaitChildExit(): SianoStreamTerminal {
        val deadline = System.nanoTime() + statusTimeoutMs * 1_000_000
        while (state.get().state == State.OPEN) {
            when (val status = process.poll()) {
                SianoChildStatus.ALIVE -> {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return SianoStreamTerminal(SianoStreamOutcome.TIMEOUT, 0)
                    try {
                        Thread.sleep(minOf(pollIntervalMs, (remaining / 1_000_000).coerceAtLeast(1)))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return SianoStreamTerminal(SianoStreamOutcome.CANCEL, 0)
                    }
                }
                is SianoChildStatus.EXITED -> return when {
                    status.code != null && status.code in 0..255 && status.signal == null ->
                        SianoStreamTerminal(SianoStreamOutcome.EXIT, status.code)
                    status.code == null && status.signal != null && status.signal in 1..255 ->
                        SianoStreamTerminal(SianoStreamOutcome.SIGNAL, status.signal)
                    else -> SianoStreamTerminal(SianoStreamOutcome.POLL_ERROR, 0)
                }
                SianoChildStatus.ERROR -> return SianoStreamTerminal(SianoStreamOutcome.POLL_ERROR, 0)
            }
        }
        return terminalOrCancel()
    }

    private fun writeEnd(terminal: SianoStreamTerminal): SianoStreamTerminal {
        val current = state.get()
        if (current.state != State.OPEN) return terminalOrCancel()
        val ending = Snapshot(State.ENDING, terminal)
        if (!state.compareAndSet(current, ending)) return terminalOrCancel()
        return try {
            output.write(header(END, END_PAYLOAD_BYTES))
            output.write(intBytes(terminal.outcome.wireValue))
            output.write(intBytes(terminal.detail))
            state.compareAndSet(ending, Snapshot(State.COMPLETE, terminal))
            state.get().terminal ?: terminal
        } catch (_: IOException) {
            failEnding(ending, SianoStreamTerminal(SianoStreamOutcome.BROKER_IO, 0))
        }
    }

    private fun fail(terminal: SianoStreamTerminal): SianoStreamTerminal {
        while (true) {
            val current = state.get()
            if (current.state == State.CANCELLED || current.state == State.COMPLETE || current.state == State.FAILED) {
                return current.terminal ?: terminal
            }
            if (state.compareAndSet(current, Snapshot(State.FAILED, terminal))) return terminal
        }
    }

    private fun failEnding(ending: Snapshot, terminal: SianoStreamTerminal): SianoStreamTerminal {
        state.compareAndSet(ending, Snapshot(State.FAILED, terminal))
        return state.get().terminal ?: terminal
    }

    private fun terminalOrCancel(): SianoStreamTerminal =
        state.get().terminal ?: SianoStreamTerminal(SianoStreamOutcome.CANCEL, 0)

    private fun header(type: Int, length: Int): ByteArray =
        MAGIC + intBytes(type) + intBytes(length)

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    companion object {
        const val MAX_DATA_BYTES = 32 * 1024
        const val STATUS_TIMEOUT_MS = 1_000L
        const val POLL_INTERVAL_MS = 10L
        const val DATA = 1
        const val END = 2
        const val END_PAYLOAD_BYTES = 8
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'I'.code.toByte(), 'A'.code.toByte(), '2'.code.toByte())
    }
}
