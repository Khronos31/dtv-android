package dev.khronos31.mirakc

internal fun px4TestDeviceName(name: String): String {
    if (name.matches(Regex("^/dev/bus/usb/\\d{3}/\\d{3}$"))) return name
    val value = Integer.toUnsignedLong(name.hashCode())
    val bus = (value / 1_000 % 999 + 1).toInt()
    val device = (value % 999 + 1).toInt()
    return "/dev/bus/usb/%03d/%03d".format(bus, device)
}
