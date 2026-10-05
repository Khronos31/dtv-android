package dev.khronos31.mirakc

/** Serializes admission attempts while allowing an explicit retry after failure. */
internal class RuntimeAdmissionRetryGate {
    private enum class State { IDLE, RUNNING, READY, FAILED, CLOSED }

    private var state = State.IDLE

    @Synchronized
    fun beginAttempt(): Boolean {
        if (state != State.IDLE && state != State.FAILED) return false
        state = State.RUNNING
        return true
    }

    @Synchronized
    fun markReady() {
        if (state == State.RUNNING) state = State.READY
    }

    @Synchronized
    fun markFailed() {
        if (state == State.RUNNING) state = State.FAILED
    }

    @Synchronized
    fun close() {
        state = State.CLOSED
    }
}
