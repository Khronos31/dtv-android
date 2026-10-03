package dev.khronos31.mirakc

internal enum class Px4DeviceModel(val adapterArgument: String) {
    Q3U4("q3u4"),
    MLT5("mlt5"),
    M1UR("m1ur"),
    S1UR("s1ur")
}

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

    private val q3u4Serial = Regex("^(\\d{14})([12])$")
    private val mlt5Serial = Regex("^\\d{15}$")

    fun select(present: List<Px4DeviceIdentity>): Px4Enclosure? {
        val plan = plan(present)
        return plan.enclosures.singleOrNull()?.takeIf {
            plan.rejections.isEmpty() && it.devices.size == present.size
        }
    }

    fun modelForProductId(productId: Int): Px4DeviceModel? = when {
        productId == Q3U4_PRODUCT_ID -> Px4DeviceModel.Q3U4
        productId in MLT5_PRODUCT_IDS -> Px4DeviceModel.MLT5
        productId == M1UR_PRODUCT_ID -> Px4DeviceModel.M1UR
        productId == S1UR_PRODUCT_ID -> Px4DeviceModel.S1UR
        else -> null
    }

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

        val q3Groups = recognized[Px4DeviceModel.Q3U4].orEmpty().groupBy { identity ->
            q3u4Serial.matchEntire(identity.serial)?.groupValues?.get(1)
        }
        q3Groups.forEach { (base, group) ->
            if (base == null) {
                rejections += "malformed Q3U4 serial"
                return@forEach
            }
            val bySuffix = group.groupBy { q3u4Serial.matchEntire(it.serial)!!.groupValues[2] }
            val first = bySuffix["1"].orEmpty()
            val second = bySuffix["2"].orEmpty()
            if (first.size != 1 || second.size != 1 || bySuffix.size != 2) {
                rejections += "incomplete or ambiguous Q3U4 pair"
                return@forEach
            }
            enclosures += enclosure(base, Px4DeviceModel.Q3U4, first.single(), second.single())
        }

        recognized[Px4DeviceModel.MLT5].orEmpty().groupBy { it.serial }.forEach { (serial, group) ->
            if (!mlt5Serial.matches(serial) || group.size != 1) {
                rejections += if (!mlt5Serial.matches(serial)) "malformed MLT5 serial" else "duplicate MLT5 identity"
            } else {
                enclosures += enclosure(serial, Px4DeviceModel.MLT5, group.single())
            }
        }

        listOf(Px4DeviceModel.M1UR, Px4DeviceModel.S1UR).forEach { model ->
            recognized[model].orEmpty().groupBy { it.serial }.forEach { (serial, group) ->
                if (!mlt5Serial.matches(serial) || group.size != 1) {
                    rejections += if (!mlt5Serial.matches(serial)) "malformed ${model.adapterArgument.uppercase()} serial"
                    else "duplicate ${model.adapterArgument.uppercase()} identity"
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
        val mlt = present.count {
            modelForProductId(it.productId) == Px4DeviceModel.MLT5 && mlt5Serial.matches(it.serial)
        }
        val q3u4 = present.count {
            modelForProductId(it.productId) == Px4DeviceModel.Q3U4 && q3u4Serial.matches(it.serial)
        }
        val plan = plan(present)
        return when {
            plan.enclosures.isNotEmpty() && plan.rejections.isEmpty() -> "running (PX4 devices ready)"
            q3u4 > 0 || present.any { modelForProductId(it.productId) == Px4DeviceModel.Q3U4 } ->
                "waiting (PX4 pair 1/2 not permitted)"
            mlt > 0 || present.isNotEmpty() -> "waiting (PX4 device identity invalid)"
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
            Px4DeviceModel.Q3U4 -> listOf(0 to false, 1 to false, 4 to false, 5 to false, 2 to true, 3 to true, 6 to true, 7 to true)
            Px4DeviceModel.MLT5 -> (0..4).map { it to null }
            Px4DeviceModel.M1UR -> listOf(0 to null)
            Px4DeviceModel.S1UR -> listOf(0 to true)
        }
        return receivers.map { (receiver, terrestrial) ->
            val supportsTerrestrial = terrestrial ?: true
            val supportsSatellite = terrestrial == false || terrestrial == null && enclosure.model != Px4DeviceModel.S1UR
            val short = enclosure.model.adapterArgument.uppercase()
            Px4TunerPlan(
                name = "PX4-$short-${enclosure.serial}-R$receiver",
                model = enclosure.model,
                instanceToken = enclosure.instanceToken,
                receiver = receiver,
                supportsTerrestrial = supportsTerrestrial,
                supportsSatellite = supportsSatellite
            )
        }
    }

}
