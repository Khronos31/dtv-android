package dev.khronos31.mirakc

/** Replaces terminally closed adapters only after their owning workers quiesce. */
internal class RuntimeResourceCycle<T>(
    initial: T,
    private val factory: () -> T
) {
    private var current = initial
    private var terminallyClosed = false

    @Synchronized
    fun current(): T = current

    /** Mark the exact generation that was closed; late cleanup cannot retire a newer one. */
    @Synchronized
    fun markTerminallyClosed(resource: T) {
        if (current === resource) terminallyClosed = true
    }

    @Synchronized
    fun replaceAfterQuiescence(quiescent: Boolean): T {
        if (!terminallyClosed) return current
        check(quiescent) { "cannot replace runtime resources before owner quiescence" }
        return factory().also {
            current = it
            terminallyClosed = false
        }
    }
}
