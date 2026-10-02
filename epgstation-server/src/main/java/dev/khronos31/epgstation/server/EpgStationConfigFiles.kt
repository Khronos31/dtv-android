package dev.khronos31.epgstation.server

import java.io.File

internal object EpgStationConfigFiles {
    private val LOG_CONFIG_NAMES = listOf(
        "operatorLogConfig",
        "serviceLogConfig",
        "epgUpdaterLogConfig"
    )

    /** Copies payload defaults only when no persistent runtime config exists yet. */
    fun ensureLogConfigs(payloadConfig: File, persistentConfig: File) {
        for (name in LOG_CONFIG_NAMES) {
            val destination = File(persistentConfig, "$name.yml")
            if (destination.isFile) continue

            val sample = File(payloadConfig, "$name.sample.yml")
            check(sample.isFile) { "upstream $name.sample.yml is missing" }
            sample.copyTo(destination, overwrite = false)
        }
    }
}
