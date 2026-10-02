package dev.khronos31.mirakc

internal enum class Px4DeviceModel(val adapterArgument: String) {
    Q3U4("q3u4"),
    MLT5("mlt5")
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
    val second: Px4DeviceIdentity? = null
)

/** Selects supported enclosures using USB product identity before serial syntax. */
internal object Px4DeviceSelector {
    const val Q3U4_PRODUCT_ID = 0x084a
    val MLT5_PRODUCT_IDS = setOf(0x024e, 0x924e)

    private val q3u4Serial = Regex("^(\\d{14})([12])$")
    private val mlt5Serial = Regex("^\\d{15}$")

    fun select(present: List<Px4DeviceIdentity>): Px4Enclosure? {
        if (present.isEmpty()) return null
        val models = present.mapNotNull { modelForProductId(it.productId) }.toSet()
        if (models.size != 1 || present.any { modelForProductId(it.productId) == null }) return null

        return when (models.single()) {
            Px4DeviceModel.Q3U4 -> selectQ3U4(present)
            Px4DeviceModel.MLT5 -> selectMlt5(present)
        }
    }

    fun modelForProductId(productId: Int): Px4DeviceModel? = when {
        productId == Q3U4_PRODUCT_ID -> Px4DeviceModel.Q3U4
        productId in MLT5_PRODUCT_IDS -> Px4DeviceModel.MLT5
        else -> null
    }

    fun waitingReason(present: List<Px4DeviceIdentity>): String {
        val mlt = present.count {
            modelForProductId(it.productId) == Px4DeviceModel.MLT5 && mlt5Serial.matches(it.serial)
        }
        val q3u4 = present.count {
            modelForProductId(it.productId) == Px4DeviceModel.Q3U4 && q3u4Serial.matches(it.serial)
        }
        return if ((mlt > 0 && q3u4 > 0) || mlt > 1 || q3u4 > 2) {
            "waiting (multiple PX4 devices)"
        } else {
            "waiting (PX4 pair 1/2 not permitted)"
        }
    }

    private fun selectQ3U4(present: List<Px4DeviceIdentity>): Px4Enclosure? {
        if (present.size != 2) return null
        val parsed = present.map { identity ->
            val match = q3u4Serial.matchEntire(identity.serial) ?: return null
            ParsedQ3Identity(identity, match.groupValues[1], match.groupValues[2].single())
        }
        if (parsed.map { it.base }.toSet().size != 1 ||
            parsed.map { it.suffix }.toSet() != setOf('1', '2')) return null
        val first = parsed.single { it.suffix == '1' }
        val second = parsed.single { it.suffix == '2' }
        return Px4Enclosure(first.base, Px4DeviceModel.Q3U4, first.identity, second.identity)
    }

    private fun selectMlt5(present: List<Px4DeviceIdentity>): Px4Enclosure? {
        if (present.size != 1) return null
        val identity = present.single()
        if (!mlt5Serial.matches(identity.serial)) return null
        return Px4Enclosure(identity.serial, Px4DeviceModel.MLT5, identity)
    }

    private data class ParsedQ3Identity(
        val identity: Px4DeviceIdentity,
        val base: String,
        val suffix: Char
    )
}
