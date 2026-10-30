package dev.khronos31.mirakc

import java.io.File

internal data class UsbPortPath(val bus: Int, val ports: List<Int>) {
    val canonical: String get() = "$bus-${ports.joinToString(".")}"
    val enclosure: String? get() = if (ports.size > 1) "$bus-${ports.dropLast(1).joinToString(".")}" else null

    fun isSiblingOf(other: UsbPortPath): Boolean =
        bus == other.bus && ports.size == other.ports.size && ports.size > 1 &&
            ports.dropLast(1) == other.ports.dropLast(1) && ports.last() == 1 && other.ports.last() == 2
}

internal data class UsbNodeAddress(val bus: Int, val device: Int)

/** Resolves the live Android USB node to Linux's physical USB port chain. */
internal object UserlandUsbTopology {
    fun nodeAddress(deviceName: String): UsbNodeAddress? {
        val match = Regex("^/dev/bus/usb/([0-9]{3})/([0-9]{3})$").matchEntire(deviceName) ?: return null
        return UsbNodeAddress(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    fun resolve(deviceName: String, sysfsRoot: File = File("/sys/bus/usb/devices")): UsbPortPath? {
        val address = nodeAddress(deviceName) ?: return null
        val matches = sysfsRoot.listFiles().orEmpty().mapNotNull { entry ->
            val bus = entry.resolve("busnum").readTextOrNull()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val device = entry.resolve("devnum").readTextOrNull()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            if (bus != address.bus || device != address.device) return@mapNotNull null
            val path = entry.resolve("devpath").readTextOrNull()?.trim() ?: return@mapNotNull null
            parsePortPath(bus, path)
        }
        return matches.singleOrNull()
    }

    fun parsePortPath(bus: Int, devpath: String): UsbPortPath? {
        if (bus !in 1..999 || devpath.isBlank()) return null
        val ports = devpath.split('.').map { it.toIntOrNull() ?: return null }
        if (ports.isEmpty() || ports.any { it !in 1..255 }) return null
        return UsbPortPath(bus, ports)
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}
