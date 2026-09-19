package dev.khronos31.mirakc

internal enum class Px4SatelliteChannelType {
    BS,
    CS
}

internal data class Px4SatelliteChannel(
    val token: String,
    val type: Px4SatelliteChannelType,
    val tsid: Int? = null
)

/**
 * Physical satellite channels observed from the live NIT on 2026-09-20.
 *
 * This is a generated/static snapshot, not a runtime NIT discovery mechanism.
 * Regenerate this inventory when the operator's satellite plan changes.
 */
internal val PX4_SATELLITE_CHANNELS = listOf(
    Px4SatelliteChannel("BS01_0", Px4SatelliteChannelType.BS, 16400),
    Px4SatelliteChannel("BS01_1", Px4SatelliteChannelType.BS, 16401),
    Px4SatelliteChannel("BS01_2", Px4SatelliteChannelType.BS, 16402),
    Px4SatelliteChannel("BS03_0", Px4SatelliteChannelType.BS, 16432),
    Px4SatelliteChannel("BS03_1", Px4SatelliteChannelType.BS, 17969),
    Px4SatelliteChannel("BS03_2", Px4SatelliteChannelType.BS, 17970),
    Px4SatelliteChannel("BS05_0", Px4SatelliteChannelType.BS, 17488),
    Px4SatelliteChannel("BS05_1", Px4SatelliteChannelType.BS, 17489),
    Px4SatelliteChannel("BS09_0", Px4SatelliteChannelType.BS, 16528),
    Px4SatelliteChannel("BS09_1", Px4SatelliteChannelType.BS, 16530),
    Px4SatelliteChannel("BS13_0", Px4SatelliteChannelType.BS, 16592),
    Px4SatelliteChannel("BS13_1", Px4SatelliteChannelType.BS, 16593),
    Px4SatelliteChannel("BS13_2", Px4SatelliteChannelType.BS, 18130),
    Px4SatelliteChannel("BS15_0", Px4SatelliteChannelType.BS, 16625),
    Px4SatelliteChannel("BS15_1", Px4SatelliteChannelType.BS, 16626),
    Px4SatelliteChannel("BS15_2", Px4SatelliteChannelType.BS, 18675),
    Px4SatelliteChannel("BS19_0", Px4SatelliteChannelType.BS, 18224),
    Px4SatelliteChannel("BS19_1", Px4SatelliteChannelType.BS, 18225),
    Px4SatelliteChannel("BS19_2", Px4SatelliteChannelType.BS, 18226),
    Px4SatelliteChannel("BS19_3", Px4SatelliteChannelType.BS, 18227),
    Px4SatelliteChannel("BS21_0", Px4SatelliteChannelType.BS, 18256),
    Px4SatelliteChannel("BS21_1", Px4SatelliteChannelType.BS, 18257),
    Px4SatelliteChannel("BS21_2", Px4SatelliteChannelType.BS, 18258),
    Px4SatelliteChannel("BS23_0", Px4SatelliteChannelType.BS, 18288),
    Px4SatelliteChannel("BS23_1", Px4SatelliteChannelType.BS, 18801),
    Px4SatelliteChannel("BS23_2", Px4SatelliteChannelType.BS, 18803),
    Px4SatelliteChannel("CS2", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS4", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS6", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS8", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS10", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS12", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS14", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS16", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS18", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS20", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS22", Px4SatelliteChannelType.CS),
    Px4SatelliteChannel("CS24", Px4SatelliteChannelType.CS)
)

internal fun renderPx4SatelliteChannelConfig(): String = buildString {
    PX4_SATELLITE_CHANNELS.forEach { channel ->
        append("  - name: ")
        append(if (channel.type == Px4SatelliteChannelType.BS) {
            "BS physical ${channel.token} (live NIT 2026-09-20)"
        } else {
            "CS physical ${channel.token} (live NIT 2026-09-20)"
        })
        append("\n    type: ")
        append(channel.type.name)
        append("\n    channel: '")
        append(channel.token)
        append("'\n")
        channel.tsid?.let { tsid ->
            append("    extra-args: '--tsid=")
            append(tsid)
            append("'\n")
        }
    }
}
