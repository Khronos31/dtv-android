package dev.khronos31.mirakc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Px4DeviceSelectorTest {
    @Test
    fun q3u4PairIsSelectedByProductIdWhenBothSerialsMatchTheMltShape() {
        val first = identity("q3-first", "123456789012341", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val second = identity("q3-second", "123456789012342", Px4DeviceSelector.Q3U4_PRODUCT_ID)

        val selected = Px4DeviceSelector.select(listOf(second, first))

        assertEquals(Px4DeviceModel.Q3U4, selected?.model)
        assertEquals("12345678901234", selected?.serial)
        assertEquals(first, selected?.first)
        assertEquals(second, selected?.second)
    }

    @Test
    fun mlt5SerialEndingInQ3SuffixIsStillAValidSingleDevice() {
        val mlt = identity("mlt", "123456789012341", 0x024e)

        val selected = Px4DeviceSelector.select(listOf(mlt))

        assertEquals(Px4DeviceModel.MLT5, selected?.model)
        assertEquals(mlt.serial, selected?.serial)
        assertEquals(mlt, selected?.first)
        assertNull(selected?.second)
    }

    @Test
    fun mixedModelsAndMultipleEnclosuresFailClosed() {
        val q3First = identity("q3-first", "123456789012341", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val q3Second = identity("q3-second", "123456789012342", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val otherMlt = identity("mlt", "987654321098765", 0x924e)

        assertNull(Px4DeviceSelector.select(listOf(q3First, otherMlt)))
        assertNull(Px4DeviceSelector.select(listOf(otherMlt, identity("mlt-2", "111111111111111", 0x024e))))
        assertNull(Px4DeviceSelector.select(listOf(
            q3First, q3Second,
            identity("q3-2-first", "987654321098761", Px4DeviceSelector.Q3U4_PRODUCT_ID),
            identity("q3-2-second", "987654321098762", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        )))
    }

    @Test
    fun unknownProductAndMalformedSerialDoNotSelectAModel() {
        assertNull(Px4DeviceSelector.select(listOf(identity("unknown", "123456789012341", 0x7777))))
        assertNull(Px4DeviceSelector.select(listOf(
            identity("q3", "123456789012343", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        )))
        assertNull(Px4DeviceSelector.select(listOf(identity("mlt", "not-a-serial", 0x924e))))
    }

    @Test
    fun generatedAdapterArgumentsCarryExplicitModelAndSelectedReceiver() {
        val q3u4 = Px4Generation("12345678901234", Px4DeviceModel.Q3U4, File("/tmp/p4"),
            terrestrialReceivers = listOf(2, 3, 6, 7), satelliteReceivers = listOf(0, 1, 4, 5))
        val mlt5 = Px4Generation("123456789012345", Px4DeviceModel.MLT5, File("/tmp/p4"),
            terrestrialReceivers = emptyList(), satelliteReceivers = emptyList(), dualReceivers = (0..4).toList())

        assertEquals(listOf("--device=12345678901234", "--model=q3u4", "--receiver=7"),
            q3u4.adapterArguments(7))
        assertEquals(listOf("--device=123456789012345", "--model=mlt5", "--receiver=4"),
            mlt5.adapterArguments(4))
    }

    private fun identity(name: String, serial: String, productId: Int) =
        Px4DeviceIdentity(name, serial, productId)
}
