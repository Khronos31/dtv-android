package dev.khronos31.mirakc

import android.content.SharedPreferences

/** One physical ISDB-T/UHF channel exposed to upstream mirakc as a GR channel. */
internal data class TerrestrialChannel(val number: Int, val name: String = "GR-$number")

internal enum class TerrestrialSettingsSource {
    UNSET,
    EXPLICIT_EMPTY,
    CUSTOM,
    MALFORMED_FALLBACK,
    FUTURE_FALLBACK
}

internal data class TerrestrialSettingsResult(
    val channels: List<TerrestrialChannel>,
    val source: TerrestrialSettingsSource,
    val error: String? = null
)

internal data class TerrestrialInputState(
    val channels: List<TerrestrialChannel>,
    val warning: String? = null
)

internal object TerrestrialChannelSettings {
    const val SCHEMA_VERSION = 1
    const val SCHEMA_PREFIX = "v$SCHEMA_VERSION|"
    private const val DEFAULT_TOKEN = "default"

    // Keep this list byte-for-byte equivalent to the original Kanto config.
    val DEFAULT_CHANNELS = listOf(
        TerrestrialChannel(16, "TOKYO MX"),
        TerrestrialChannel(21, "フジテレビジョン"),
        TerrestrialChannel(22, "TBS"),
        TerrestrialChannel(23, "テレビ東京"),
        TerrestrialChannel(24, "テレビ朝日"),
        TerrestrialChannel(25, "日本テレビ"),
        TerrestrialChannel(26, "NHK Eテレ東京"),
        TerrestrialChannel(27, "NHK総合・東京"),
        TerrestrialChannel(30, "チバテレビ"),
        TerrestrialChannel(31, "tvk"),
        TerrestrialChannel(32, "テレ玉")
    )

    fun parseStored(raw: String?, fallback: List<TerrestrialChannel> = DEFAULT_CHANNELS): TerrestrialSettingsResult {
        if (raw == null) return TerrestrialSettingsResult(fallback, TerrestrialSettingsSource.UNSET)
        if (!raw.startsWith("v")) return fallbackResult(raw, fallback, TerrestrialSettingsSource.MALFORMED_FALLBACK)
        val separator = raw.indexOf('|')
        if (separator <= 1) return fallbackResult(raw, fallback, TerrestrialSettingsSource.MALFORMED_FALLBACK)
        val version = raw.substring(1, separator).toIntOrNull()
            ?: return fallbackResult(raw, fallback, TerrestrialSettingsSource.MALFORMED_FALLBACK)
        if (version > SCHEMA_VERSION) return fallbackResult(raw, fallback, TerrestrialSettingsSource.FUTURE_FALLBACK)
        if (version != SCHEMA_VERSION) return fallbackResult(raw, fallback, TerrestrialSettingsSource.MALFORMED_FALLBACK)
        val input = raw.substring(separator + 1)
        if (input == DEFAULT_TOKEN) return TerrestrialSettingsResult(DEFAULT_CHANNELS, TerrestrialSettingsSource.UNSET)
        if (input.isBlank()) return TerrestrialSettingsResult(emptyList(), TerrestrialSettingsSource.EXPLICIT_EMPTY)
        return try {
            TerrestrialSettingsResult(parseInput(input), TerrestrialSettingsSource.CUSTOM)
        } catch (error: IllegalArgumentException) {
            fallbackResult(raw, fallback, TerrestrialSettingsSource.MALFORMED_FALLBACK, error.message)
        }
    }

    fun parseInput(input: String): List<TerrestrialChannel> {
        if (input.isBlank()) return emptyList()
        val numbers = linkedSetOf<Int>()
        input.split(',').forEach { rawToken ->
            val token = rawToken.trim()
            require(token.isNotEmpty()) { "empty channel item" }
            val parts = token.split('-')
            require(parts.size == 1 || parts.size == 2) { "malformed channel range: $token" }
            require(parts.all { it.matches(Regex("[0-9]+")) }) { "invalid channel: $token" }
            val first = parts[0].toIntOrNull() ?: throw IllegalArgumentException("invalid channel: $token")
            val last = if (parts.size == 2) {
                parts[1].toIntOrNull() ?: throw IllegalArgumentException("invalid channel range: $token")
            } else first
            require(first <= last) { "descending channel range: $token" }
            (first..last).forEach { number ->
                require(number in 13..62) { "channel outside physical UHF range: $number" }
                numbers += number
            }
        }
        return numbers.sorted().map { TerrestrialChannel(it) }
    }

    fun serialize(channels: List<TerrestrialChannel>): String {
        val numbers = channels.map { it.number }
        require(numbers == numbers.distinct().sorted()) { "channels must be unique and sorted" }
        require(numbers.all { it in 13..62 }) { "channel outside physical UHF range" }
        return if (channels == DEFAULT_CHANNELS) {
            SCHEMA_PREFIX + DEFAULT_TOKEN
        } else {
            SCHEMA_PREFIX + numbers.joinToString(",")
        }
    }

    fun inputText(channels: List<TerrestrialChannel>): String = channels.joinToString(",") { it.number.toString() }

    private fun fallbackResult(
        raw: String,
        fallback: List<TerrestrialChannel>,
        source: TerrestrialSettingsSource,
        detail: String? = null
    ): TerrestrialSettingsResult = TerrestrialSettingsResult(
        fallback,
        source,
        "Invalid terrestrial channel settings '$raw'${detail?.let { ": $it" } ?: ""}; using last-known-good/default"
    )
}

internal fun renderTerrestrialChannelConfig(
    channels: List<TerrestrialChannel>,
    appendListHeaderWhenEmpty: Boolean = false
): String = buildString {
    if (channels.isEmpty()) {
        append(if (appendListHeaderWhenEmpty) "channels:\n" else "channels: []\n")
    } else {
        append("channels:\n")
        channels.forEach { channel ->
            append("  - name: ").append(channel.name).append("\n")
            append("    type: GR\n")
            append("    channel: '").append(channel.number).append("'\n")
        }
    }
}

internal interface StringSettings {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun transaction(changes: Map<String, String?>) {
        changes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }
}

internal class AndroidStringSettings(private val preferences: SharedPreferences) : StringSettings {
    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) { "unable to save terrestrial settings" }
    }

    override fun remove(key: String) {
        check(preferences.edit().remove(key).commit()) { "unable to remove terrestrial setting" }
    }

    override fun transaction(changes: Map<String, String?>) {
        val editor = preferences.edit()
        changes.forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        check(editor.commit()) { "unable to commit terrestrial settings" }
    }
}

/** Small transactional preference facade, intentionally independent of Android for JVM tests. */
internal class TerrestrialChannelSettingsStore(private val values: StringSettings) {
    data class Snapshot(
        val active: TerrestrialSettingsResult,
        val pending: TerrestrialSettingsResult?
    )

    fun snapshot(): Snapshot {
        val storedLastGood = values.get(LAST_KNOWN_GOOD_KEY)
        val lastGood = TerrestrialChannelSettings.parseStored(storedLastGood).takeIf {
            it.source != TerrestrialSettingsSource.MALFORMED_FALLBACK &&
                it.source != TerrestrialSettingsSource.FUTURE_FALLBACK
        }?.channels ?: TerrestrialChannelSettings.DEFAULT_CHANNELS
        val active = TerrestrialChannelSettings.parseStored(values.get(ACTIVE_KEY), lastGood)
        val pendingRaw = values.get(PENDING_KEY)
        return Snapshot(active, pendingRaw?.let { TerrestrialChannelSettings.parseStored(it) })
    }

    fun active(): TerrestrialSettingsResult = snapshot().active

    fun savePending(input: String) {
        val channels = TerrestrialChannelSettings.parseInput(input)
        values.put(PENDING_KEY, TerrestrialChannelSettings.serialize(channels))
    }

    fun activatePending() {
        val pendingRaw = values.get(PENDING_KEY) ?: throw IllegalStateException("no pending terrestrial settings")
        val pending = TerrestrialChannelSettings.parseStored(pendingRaw)
        require(pending.source == TerrestrialSettingsSource.CUSTOM || pending.source == TerrestrialSettingsSource.EXPLICIT_EMPTY) {
            "pending terrestrial settings are invalid"
        }
        val current = snapshot().active
        values.transaction(
            mapOf(
                LAST_KNOWN_GOOD_KEY to TerrestrialChannelSettings.serialize(current.channels),
                APPLYING_KEY to TerrestrialChannelSettings.serialize(current.channels),
                ACTIVE_KEY to TerrestrialChannelSettings.serialize(pending.channels),
                PENDING_KEY to null
            )
        )
    }

    fun restoreLastKnownGood() {
        val lastGood = values.get(LAST_KNOWN_GOOD_KEY) ?: return
        val parsed = TerrestrialChannelSettings.parseStored(lastGood)
        require(parsed.source != TerrestrialSettingsSource.MALFORMED_FALLBACK && parsed.source != TerrestrialSettingsSource.FUTURE_FALLBACK) {
            "last-known-good terrestrial settings are invalid"
        }
        values.transaction(
            mapOf(
                ACTIVE_KEY to TerrestrialChannelSettings.serialize(parsed.channels),
                APPLYING_KEY to null
            )
        )
    }

    fun commitApply() {
        values.remove(APPLYING_KEY)
    }

    /** Recover an apply interrupted by process death before startup was proven. */
    fun recoverIncompleteApply() {
        val applying = values.get(APPLYING_KEY) ?: return
        val parsed = TerrestrialChannelSettings.parseStored(applying)
        require(parsed.source != TerrestrialSettingsSource.MALFORMED_FALLBACK && parsed.source != TerrestrialSettingsSource.FUTURE_FALLBACK) {
            "in-progress terrestrial settings marker is invalid"
        }
        values.transaction(
            mapOf(
                ACTIVE_KEY to TerrestrialChannelSettings.serialize(parsed.channels),
                APPLYING_KEY to null
            )
        )
    }

    fun inputState(): TerrestrialInputState = terrestrialInputState(snapshot())

    companion object {
        const val PENDING_KEY = "terrestrial_channels_pending"
        const val ACTIVE_KEY = "terrestrial_channels_active"
        const val LAST_KNOWN_GOOD_KEY = "terrestrial_channels_last_known_good"
        const val APPLYING_KEY = "terrestrial_channels_apply_in_progress"
    }
}

/**
 * Select the value shown in the editor without making an invalid pending value
 * look like a valid one. A valid pending value must survive Activity recreation
 * so that a subsequent save/apply action cannot overwrite it with active.
 */
internal fun terrestrialInputState(snapshot: TerrestrialChannelSettingsStore.Snapshot): TerrestrialInputState {
    val pending = snapshot.pending
    if (pending == null) return TerrestrialInputState(snapshot.active.channels, snapshot.active.error)
    if (pending.source == TerrestrialSettingsSource.MALFORMED_FALLBACK ||
        pending.source == TerrestrialSettingsSource.FUTURE_FALLBACK
    ) {
        return TerrestrialInputState(snapshot.active.channels, pending.error ?: snapshot.active.error)
    }
    return TerrestrialInputState(pending.channels, pending.error ?: snapshot.active.error)
}
