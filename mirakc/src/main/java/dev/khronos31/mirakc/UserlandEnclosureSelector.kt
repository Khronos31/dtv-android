package dev.khronos31.mirakc

internal data class UserlandUsbIdentity(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val path: UsbPortPath?,
    val permitted: Boolean = true
)

internal val UserlandUsbIdentity.deviceKey: String
    get() = path?.canonical ?: "node:$deviceName"

internal data class UserlandEnclosure(
    val product: UserlandProduct,
    val token: String,
    val devices: List<UserlandUsbIdentity>
)

internal object UserlandEnclosureSelector {
    fun pix(devices: List<UserlandUsbIdentity>): List<UserlandEnclosure> =
        devices.filter { it.vendorId == 0x06b8 && it.productId in setOf(0x102b, 0x103b) }
            .groupBy { it.deviceKey }
            .filterValues { it.size == 1 }
            .map { (path, values) -> UserlandEnclosure(UserlandProduct.PIX, userlandInstanceToken("pix", path), values) }
            .sortedBy { it.token }

    fun asicen(devices: List<UserlandUsbIdentity>): List<UserlandEnclosure> {
        val runtime = devices.filter {
            it.vendorId == AsicenProvisioningMachine.RUNTIME_VENDOR_ID &&
                it.productId in AsicenProvisioningMachine.RUNTIME_IDS && it.path != null
        }
        val paired = runtime.filter { it.productId in PAIRED_RUNTIME_IDS }
        val pairedGroups = paired.groupBy { it.path!!.enclosure }
        val result = mutableListOf<UserlandEnclosure>()
        pairedGroups.forEach { (enclosurePath, functions) ->
            val primary = functions.filter { it.path!!.ports.lastOrNull() == 1 }
            val sibling = functions.filter { it.path!!.ports.lastOrNull() == 2 }
            if (enclosurePath != null && primary.size == 1 && sibling.size == 1 && functions.size == 2 &&
                primary.single().path!!.isSiblingOf(sibling.single().path!!) &&
                primary.single().productId == sibling.single().productId
            ) {
                result += UserlandEnclosure(
                    UserlandProduct.ASICEN, userlandInstanceToken("asicen", primary.single().path!!.canonical),
                    listOf(primary.single(), sibling.single())
                )
            }
        }
        runtime.filter { it.productId in SINGLE_RUNTIME_IDS }.forEach { device ->
            result += UserlandEnclosure(
                UserlandProduct.ASICEN, userlandInstanceToken("asicen", device.path!!.canonical), listOf(device)
            )
        }
        return result.sortedBy { it.token }
    }

    private val SINGLE_RUNTIME_IDS = setOf(0x0001, 0x0003)
    private val PAIRED_RUNTIME_IDS = setOf(0x0004, 0x0005, 0x0006)
}

internal fun userlandInstanceToken(prefix: String, path: String): String =
    "$prefix-${java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
        .take(6).joinToString("") { "%02x".format(it) }}"
