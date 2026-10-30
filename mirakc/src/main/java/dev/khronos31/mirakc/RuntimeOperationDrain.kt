package dev.khronos31.mirakc

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal object RuntimeOperationDrain {
    @Throws(InterruptedException::class)
    fun awaitTermination(executor: ExecutorService, timeoutMs: Long) {
        if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("service operations did not terminate within ${timeoutMs}ms")
        }
    }
}
