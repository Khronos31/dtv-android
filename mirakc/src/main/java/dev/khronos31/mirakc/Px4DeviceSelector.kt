package dev.khronos31.mirakc

internal enum class Px4DeviceModel(
    val adapterArgument: String,
    val productIds: Set<Int>,
    val bridgeCount: Int,
    val receiverCount: Int,
    private val receiverProfile: Px4ReceiverProfile
) {
    Q3U4("q3u4", setOf(0x084a), 2, 8, Px4ReceiverProfile.Q3_FIXED),
    W3U4("w3u4", setOf(0x083f), 1, 4, Px4ReceiverProfile.W3_FIXED),
    MLT5("mlt5", setOf(0x024e, 0x924e), 1, 5, Px4ReceiverProfile.DUAL_SYSTEM),
    W3PE4("w3pe4", setOf(0x023f), 1, 4, Px4ReceiverProfile.W3_FIXED),
    W3PE5("w3pe5", setOf(0x073f), 1, 4, Px4ReceiverProfile.W3_FIXED),
    Q3PE4("q3pe4", setOf(0x024a), 2, 8, Px4ReceiverProfile.Q3_FIXED),
    Q3PE5("q3pe5", setOf(0x074a), 2, 8, Px4ReceiverProfile.Q3_FIXED),
    MLT8PE3("mlt8pe3", setOf(0x0252), 1, 3, Px4ReceiverProfile.DUAL_SYSTEM),
    MLT8PE5("mlt8pe5", setOf(0x0253), 1, 5, Px4ReceiverProfile.DUAL_SYSTEM),
    DTV02A4TSP("dtv02a4tsp", setOf(0x0254), 1, 4, Px4ReceiverProfile.DUAL_SYSTEM),
    M1UR("m1ur", setOf(0x0854), 1, 1, Px4ReceiverProfile.DUAL_SYSTEM),
    S1UR("s1ur", setOf(0x0855), 1, 1, Px4ReceiverProfile.TERRESTRIAL_ONLY),
    DTV03A1TU("dtv03a1tu", setOf(0x0052), 1, 1, Px4ReceiverProfile.TERRESTRIAL_ONLY),
    DTV021T1SU("dtv021t1su", setOf(0x004b), 1, 1, Px4ReceiverProfile.DUAL_SYSTEM),
    DTV02A1T1SU("dtv02a1t1su", setOf(0x084b), 1, 1, Px4ReceiverProfile.DUAL_SYSTEM);

    fun capabilitiesFor(receiver: Int): Px4ReceiverCapabilities? {
        if (receiver !in 0 until receiverCount) return null
        return when (receiverProfile) {
            Px4ReceiverProfile.Q3_FIXED -> if (receiver % 4 < 2) {
                Px4ReceiverCapabilities(terrestrial = false, satellite = true)
            } else {
                Px4ReceiverCapabilities(terrestrial = true, satellite = false)
            }
            Px4ReceiverProfile.W3_FIXED -> if (receiver < 2) {
                Px4ReceiverCapabilities(terrestrial = false, satellite = true)
            } else {
                Px4ReceiverCapabilities(terrestrial = true, satellite = false)
            }
            Px4ReceiverProfile.DUAL_SYSTEM -> Px4ReceiverCapabilities(true, true)
            Px4ReceiverProfile.TERRESTRIAL_ONLY -> Px4ReceiverCapabilities(true, false)
        }
    }
}

private enum class Px4ReceiverProfile { Q3_FIXED, W3_FIXED, DUAL_SYSTEM, TERRESTRIAL_ONLY }

internal data class Px4ReceiverCapabilities(val terrestrial: Boolean, val satellite: Boolean)

internal data class Px4DeviceIdentity(
    val deviceName: String,
    val serial: String,
    val productId: Int
)

internal data class Px4Enclosure(
    val serial: String,
    val model: Px4DeviceModel,
    val first: Px4DeviceIdentity,
    val second: Px4DeviceIdentity? = null,
    val instanceToken: String = "px4-${model.adapterArgument}-$serial"
) {
    val devices: List<Px4DeviceIdentity> get() = listOfNotNull(first, second)
}

internal data class Px4TunerPlan(
    val name: String,
    val model: Px4DeviceModel,
    val instanceToken: String,
    val receiver: Int,
    val supportsTerrestrial: Boolean,
    val supportsSatellite: Boolean
)

internal data class Px4Plan(
    val enclosures: List<Px4Enclosure>,
    val tuners: List<Px4TunerPlan>,
    val rejections: List<String>
)

internal data class UnmappedPx4Device(val deviceName: String, val model: Px4DeviceModel)

/** Snapshot used to invalidate an owner from the detach broadcast before a later device-list scan. */
internal data class Px4OwnerIdentity(val instanceToken: String, val deviceNames: Set<String>)

internal object Px4OwnerDetachPolicy {
    fun matchingTokens(owners: List<Px4OwnerIdentity>, detachedDeviceName: String): Set<String> =
        owners.filter { detachedDeviceName in it.deviceNames }.mapTo(linkedSetOf()) { it.instanceToken }
}

/** Selects supported enclosures using USB product identity before serial syntax. */
internal object Px4DeviceSelector {
    const val Q3U4_PRODUCT_ID = 0x084a
    val MLT5_PRODUCT_IDS = setOf(0x024e, 0x924e)
    const val M1UR_PRODUCT_ID = 0x0854
    const val S1UR_PRODUCT_ID = 0x0855

    private val dualBridgeSerial = Regex("^(\\d{14})([12])$")
    private val singleBridgeSerial = Regex("^\\d{15}$")

    fun select(present: List<Px4DeviceIdentity>): Px4Enclosure? {
        val plan = plan(present)
        return plan.enclosures.singleOrNull()?.takeIf {
            plan.rejections.isEmpty() && it.devices.size == present.size
        }
    }

    fun modelForProductId(productId: Int): Px4DeviceModel? =
        Px4DeviceModel.entries.firstOrNull { productId in it.productIds }

    /** Keep detected but unpermitted/incomplete devices visible beside mapped enclosures. */
    fun unmappedDetectedDevices(detected: List<Pair<String, Int>>, mappedDeviceNames: Set<String>): List<UnmappedPx4Device> =
        detected.filterNot { (deviceName, _) -> deviceName in mappedDeviceNames }
            .mapNotNull { (deviceName, productId) ->
                modelForProductId(productId)?.let { UnmappedPx4Device(deviceName, it) }
            }
            .sortedWith(compareBy({ it.model.ordinal }, { it.deviceName }))

    /** Build a deterministic, fail-closed plan for every independently identifiable enclosure. */
    fun plan(present: List<Px4DeviceIdentity>): Px4Plan {
        val enclosures = mutableListOf<Px4Enclosure>()
        val rejections = mutableListOf<String>()
        val duplicatePaths = present.groupBy { it.deviceName }
            .filter { (name, identities) -> name.isBlank() || identities.size > 1 }
        rejections += List(duplicatePaths.size) { "duplicate USB path" }
        val unambiguous = present.filterNot { it.deviceName in duplicatePaths }
        val recognized = unambiguous.groupBy { modelForProductId(it.productId) }

        recognized[null].orEmpty().forEach { rejections += "unsupported product" }

        Px4DeviceModel.entries.filter { it.bridgeCount == 2 }.forEach { model ->
            val q3Groups = recognized[model].orEmpty().groupBy { identity ->
                dualBridgeSerial.matchEntire(identity.serial)?.groupValues?.get(1)
            }
            q3Groups.forEach groupLoop@ { (base, group) ->
                if (base == null) {
                    rejections += "malformed ${model.adapterArgument.uppercase()} serial"
                    return@groupLoop
                }
                val bySuffix = group.groupBy { dualBridgeSerial.matchEntire(it.serial)!!.groupValues[2] }
                val first = bySuffix["1"].orEmpty()
                val second = bySuffix["2"].orEmpty()
                if (first.size != 1 || second.size != 1 || bySuffix.size != 2) {
                    rejections += "incomplete or ambiguous ${model.adapterArgument.uppercase()} pair"
                    return@groupLoop
                }
                enclosures += enclosure(base, model, first.single(), second.single())
            }
        }

        Px4DeviceModel.entries.filter { it.bridgeCount == 1 }.forEach { model ->
            recognized[model].orEmpty().groupBy { it.serial }.forEach { (serial, group) ->
                if (!singleBridgeSerial.matches(serial) || group.size != 1) {
                    rejections += if (!singleBridgeSerial.matches(serial)) {
                        "malformed ${model.adapterArgument.uppercase()} serial"
                    } else {
                        "duplicate ${model.adapterArgument.uppercase()} identity"
                    }
                } else {
                    enclosures += enclosure(serial, model, group.single())
                }
            }
        }

        val ordered = enclosures.sortedWith(compareBy<Px4Enclosure>({ it.model.ordinal }, { it.serial }, { it.first.productId }))
        val tuners = ordered.flatMap(::tunersFor)
        return Px4Plan(ordered, tuners, rejections.sorted())
    }

    fun waitingReason(present: List<Px4DeviceIdentity>): String {
        val plan = plan(present)
        return when {
            plan.enclosures.isNotEmpty() && plan.rejections.isEmpty() -> "running (PX4 devices ready)"
            present.any { modelForProductId(it.productId)?.bridgeCount == 2 } ->
                "waiting (PX4 pair 1/2 not permitted)"
            present.isNotEmpty() -> "waiting (PX4 device identity invalid)"
            else -> "disabled (PX4 not connected)"
        }
    }

    private fun enclosure(
        serial: String,
        model: Px4DeviceModel,
        first: Px4DeviceIdentity,
        second: Px4DeviceIdentity? = null
    ) = Px4Enclosure(serial, model, first, second, "px4-${model.adapterArgument}-${first.productId.toString(16)}-$serial")

    internal fun tunersFor(enclosure: Px4Enclosure): List<Px4TunerPlan> {
        val receivers = when (enclosure.model) {
            Px4DeviceModel.Q3U4, Px4DeviceModel.Q3PE4, Px4DeviceModel.Q3PE5 ->
                listOf(0, 1, 4, 5, 2, 3, 6, 7)
            else -> (0 until enclosure.model.receiverCount).toList()
        }
        return receivers.mapNotNull { receiver ->
            val capabilities = enclosure.model.capabilitiesFor(receiver) ?: return@mapNotNull null
            val short = enclosure.model.adapterArgument.uppercase()
            Px4TunerPlan(
                name = "PX4-$short-${enclosure.serial}-R$receiver",
                model = enclosure.model,
                instanceToken = enclosure.instanceToken,
                receiver = receiver,
                supportsTerrestrial = capabilities.terrestrial,
                supportsSatellite = capabilities.satellite
            )
        }
    }

}
