package dev.khronos31.mirakc

/** User-facing EPG collection interval. `@every` is intentionally not cron syntax. */
internal object EpgUpdateIntervalSettings {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 1440
    const val DEFAULT_MINUTES = 10
    const val KEY = "epg-update-interval-minutes"

    fun parseInput(input: String): Int {
        val minutes = input.trim().toIntOrNull()
            ?: throw IllegalArgumentException("更新間隔は1〜1440分の整数で入力してください")
        require(minutes in MIN_MINUTES..MAX_MINUTES) {
            "更新間隔は1〜1440分で入力してください"
        }
        return minutes
    }

    fun parseStored(raw: String?): Int =
        raw?.toIntOrNull()?.takeIf { it in MIN_MINUTES..MAX_MINUTES } ?: DEFAULT_MINUTES
}

internal class EpgUpdateIntervalStore(private val values: StringSettings) {
    fun minutes(): Int = EpgUpdateIntervalSettings.parseStored(values.get(EpgUpdateIntervalSettings.KEY))

    fun save(input: String): Int {
        val minutes = EpgUpdateIntervalSettings.parseInput(input)
        values.put(EpgUpdateIntervalSettings.KEY, minutes.toString())
        return minutes
    }
}
