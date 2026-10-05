package dev.khronos31.mirakc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun m1urAndS1urAreDistinctSingleReceiverProfiles() {
        val m1 = identity("m1", "000000000000001", Px4DeviceSelector.M1UR_PRODUCT_ID)
        val s1 = identity("s1", "000000000000001", Px4DeviceSelector.S1UR_PRODUCT_ID)

        val plan = Px4DeviceSelector.plan(listOf(s1, m1))

        assertEquals(listOf(Px4DeviceModel.M1UR, Px4DeviceModel.S1UR),
            plan.enclosures.map { it.model })
        assertEquals(setOf("PX4-M1UR-000000000000001-R0", "PX4-S1UR-000000000000001-R0"),
            plan.tuners.map { it.name }.toSet())
        assertEquals(2, plan.tuners.size)
        assertTrue(plan.tuners.single { it.model == Px4DeviceModel.M1UR }.supportsSatellite)
        assertTrue(plan.tuners.single { it.model == Px4DeviceModel.M1UR }.supportsTerrestrial)
        assertFalse(plan.tuners.single { it.model == Px4DeviceModel.S1UR }.supportsSatellite)
        assertTrue(plan.tuners.single { it.model == Px4DeviceModel.S1UR }.supportsTerrestrial)
        assertEquals(0, plan.tuners.single { it.model == Px4DeviceModel.M1UR }.receiver)
        assertEquals(0, plan.tuners.single { it.model == Px4DeviceModel.S1UR }.receiver)
        assertTrue(plan.enclosures.map { it.instanceToken }.toSet().size == 2)
        assertTrue(plan.rejections.isEmpty())
    }

    @Test
    fun multipleQ3PairsAndSingleBridgeFamiliesProduceStableIndependentIds() {
        val q3A1 = identity("a1", "111111111111111", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val q3A2 = identity("a2", "111111111111112", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val q3B1 = identity("b1", "222222222222221", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val q3B2 = identity("b2", "222222222222222", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val mlt = identity("mlt", "333333333333333", 0x024e)
        val m1 = identity("m1", "000000000000001", Px4DeviceSelector.M1UR_PRODUCT_ID)
        val s1 = identity("s1", "000000000000001", Px4DeviceSelector.S1UR_PRODUCT_ID)
        val present = listOf(q3B2, m1, q3A1, mlt, s1, q3B1, q3A2)

        val expected = Px4DeviceSelector.plan(present)
        val shuffled = Px4DeviceSelector.plan(present.reversed())

        assertEquals(5, expected.enclosures.size)
        assertEquals(23, expected.tuners.size) // 8 + 8 + 5 + 1 + 1
        assertEquals(expected.enclosures.map { it.instanceToken },
            shuffled.enclosures.map { it.instanceToken })
        assertEquals(expected.tuners.map { it.name }, shuffled.tuners.map { it.name })
        assertEquals(expected.tuners.size, expected.tuners.map { it.name }.toSet().size)
        assertEquals(expected.enclosures.size, expected.enclosures.map { it.instanceToken }.toSet().size)
        val mltTuners = expected.tuners.filter { it.model == Px4DeviceModel.MLT5 }
        assertEquals((0..4).toList(), mltTuners.map { it.receiver })
        assertTrue(mltTuners.all { it.supportsTerrestrial && it.supportsSatellite })
        val q3Tuners = expected.tuners.filter { it.model == Px4DeviceModel.Q3U4 }
        assertEquals(setOf(2, 3, 6, 7), q3Tuners.filter { it.supportsTerrestrial && !it.supportsSatellite }.map { it.receiver }.toSet())
        assertEquals(setOf(0, 1, 4, 5), q3Tuners.filter { it.supportsSatellite && !it.supportsTerrestrial }.map { it.receiver }.toSet())
        assertTrue(expected.enclosures.all { it.instanceToken.matches(Regex("[A-Za-z0-9_.-]{1,80}")) })
        assertTrue(expected.rejections.isEmpty())
    }

    @Test
    fun incompleteOrAmbiguousEnclosureDoesNotSuppressUnrelatedValidDevice() {
        val incompleteQ3 = identity("q3-half", "123456789012341", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val duplicatedM1 = listOf(
            identity("m1-a", "000000000000001", Px4DeviceSelector.M1UR_PRODUCT_ID),
            identity("m1-b", "000000000000001", Px4DeviceSelector.M1UR_PRODUCT_ID)
        )
        val mlt = identity("mlt", "987654321098765", 0x924e)
        val validS1 = identity("s1", "000000000000001", Px4DeviceSelector.S1UR_PRODUCT_ID)

        val plan = Px4DeviceSelector.plan(listOf(incompleteQ3, mlt) + duplicatedM1 + validS1)

        assertEquals(setOf(Px4DeviceModel.MLT5, Px4DeviceModel.S1UR),
            plan.enclosures.map { it.model }.toSet())
        assertEquals(setOf(mlt.deviceName, validS1.deviceName),
            plan.enclosures.flatMap { it.devices }.map { it.deviceName }.toSet())
        assertEquals(2, plan.rejections.size)
    }

    @Test
    fun q3DevicesArePairedOnlyByTheirFourteenDigitBaseAndOneTwoSuffix() {
        val firstA = identity("a1", "111111111111111", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val secondB = identity("b2", "222222222222222", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val firstB = identity("b1", "222222222222221", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        val secondA = identity("a2", "111111111111112", Px4DeviceSelector.Q3U4_PRODUCT_ID)

        val plan = Px4DeviceSelector.plan(listOf(firstA, secondB, firstB, secondA))

        assertEquals(2, plan.enclosures.size)
        assertTrue(plan.enclosures.all { it.model == Px4DeviceModel.Q3U4 && it.devices.size == 2 })
        assertTrue(plan.enclosures.all { it.first.serial.endsWith("1") && it.second?.serial?.endsWith("2") == true })
        assertEquals(16, plan.tuners.size)
        assertTrue(plan.rejections.isEmpty())
    }

    @Test
    fun duplicateUsbPathIsRejectedWithoutSuppressingIndependentDevices() {
        val duplicatePath = "usb-1"
        val ambiguous = listOf(
            identity(duplicatePath, "000000000000001", Px4DeviceSelector.M1UR_PRODUCT_ID),
            identity(duplicatePath, "000000000000002", Px4DeviceSelector.M1UR_PRODUCT_ID)
        )
        val valid = identity("usb-2", "000000000000003", Px4DeviceSelector.S1UR_PRODUCT_ID)

        val plan = Px4DeviceSelector.plan(ambiguous + valid)

        assertEquals(listOf(valid.deviceName), plan.enclosures.flatMap { it.devices }.map { it.deviceName })
        assertEquals(1, plan.rejections.size)
    }

    @Test
    fun detachInvalidatesThePreviouslyOwnedPathEvenIfTheSameIdentityIsVisibleAgain() {
        val detached = Px4OwnerIdentity("px4-q3u4-owner", setOf("usb-path-1", "usb-path-2"))
        val unrelated = Px4OwnerIdentity("px4-mlt5-owner", setOf("usb-path-3"))

        // The detach receiver matches the saved owner snapshot, not a later
        // device-list scan that may already contain a reinserted same serial.
        assertEquals(setOf(detached.instanceToken),
            Px4OwnerDetachPolicy.matchingTokens(listOf(detached, unrelated), "usb-path-2"))
        assertTrue(Px4OwnerDetachPolicy.matchingTokens(listOf(detached, unrelated), "usb-path-4").isEmpty())
    }

    @Test
    fun generatedAdapterArgumentsCarryExplicitModelAndSelectedReceiver() {
        val q3u4 = Px4Generation("12345678901234", Px4DeviceModel.Q3U4, File("/tmp/p4"),
            "px4-q3u4-12345678901234", emptyList())
        val mlt5 = Px4Generation("123456789012345", Px4DeviceModel.MLT5, File("/tmp/p4"),
            "px4-mlt5-123456789012345", emptyList())

        assertEquals(listOf("--device=12345678901234", "--instance=px4-q3u4-12345678901234",
            "--model=q3u4", "--receiver=7"),
            q3u4.adapterArguments(7))
        assertEquals(listOf("--device=123456789012345", "--instance=px4-mlt5-123456789012345",
            "--model=mlt5", "--receiver=4"),
            mlt5.adapterArguments(4))
    }

    @Test
    fun everyPinnedPx4ProductIdMapsToItsExactEnclosureAndReceiverProfile() {
        val expectedProducts = mapOf(
            0x084a to Px4DeviceModel.Q3U4,
            0x083f to Px4DeviceModel.W3U4,
            0x024e to Px4DeviceModel.MLT5,
            0x924e to Px4DeviceModel.MLT5,
            0x023f to Px4DeviceModel.W3PE4,
            0x073f to Px4DeviceModel.W3PE5,
            0x024a to Px4DeviceModel.Q3PE4,
            0x074a to Px4DeviceModel.Q3PE5,
            0x0252 to Px4DeviceModel.MLT8PE3,
            0x0253 to Px4DeviceModel.MLT8PE5,
            0x0254 to Px4DeviceModel.DTV02A4TSP,
            0x0854 to Px4DeviceModel.M1UR,
            0x0855 to Px4DeviceModel.S1UR,
            0x0052 to Px4DeviceModel.DTV03A1TU,
            0x004b to Px4DeviceModel.DTV021T1SU,
            0x084b to Px4DeviceModel.DTV02A1T1SU
        )
        val expectedProductIds = expectedProducts.keys
        expectedProductIds.forEach { productId ->
            assertEquals(expectedProducts[productId], Px4DeviceSelector.modelForProductId(productId))
        }

        assertEquals(expectedProductIds, Px4DeviceModel.entries.flatMap { it.productIds }.toSet())
        assertNull(Px4DeviceSelector.modelForProductId(0x7777))

        val profileCases = listOf(
            Px4DeviceModel.Q3U4 to (2 to (8 to listOf(false to true, false to true, true to false,
                true to false, false to true, false to true, true to false, true to false))),
            Px4DeviceModel.Q3PE4 to (2 to (8 to listOf(false to true, false to true, true to false,
                true to false, false to true, false to true, true to false, true to false))),
            Px4DeviceModel.Q3PE5 to (2 to (8 to listOf(false to true, false to true, true to false,
                true to false, false to true, false to true, true to false, true to false))),
            Px4DeviceModel.W3U4 to (1 to (4 to listOf(false to true, false to true, true to false, true to false))),
            Px4DeviceModel.W3PE4 to (1 to (4 to listOf(false to true, false to true, true to false, true to false))),
            Px4DeviceModel.W3PE5 to (1 to (4 to listOf(false to true, false to true, true to false, true to false))),
            Px4DeviceModel.MLT5 to (1 to (5 to List(5) { true to true })),
            Px4DeviceModel.MLT8PE3 to (1 to (3 to List(3) { true to true })),
            Px4DeviceModel.MLT8PE5 to (1 to (5 to List(5) { true to true })),
            Px4DeviceModel.DTV02A4TSP to (1 to (4 to List(4) { true to true })),
            Px4DeviceModel.M1UR to (1 to (1 to listOf(true to true))),
            Px4DeviceModel.S1UR to (1 to (1 to listOf(true to false))),
            Px4DeviceModel.DTV03A1TU to (1 to (1 to listOf(true to false))),
            Px4DeviceModel.DTV021T1SU to (1 to (1 to listOf(true to true))),
            Px4DeviceModel.DTV02A1T1SU to (1 to (1 to listOf(true to true)))
        ).toMap()
        assertEquals(Px4DeviceModel.entries.toSet(), profileCases.keys)
        profileCases.forEach { (model, shape) ->
            val (bridgeCount, receiverDetails) = shape
            val (receiverCount, expectedCapabilities) = receiverDetails
            assertEquals(bridgeCount, model.bridgeCount)
            assertEquals(receiverCount, model.receiverCount)
            assertEquals(expectedCapabilities.size, receiverCount)
            expectedCapabilities.forEachIndexed { receiver, (terrestrial, satellite) ->
                val actual = model.capabilitiesFor(receiver)
                assertEquals(terrestrial to satellite, actual?.let { it.terrestrial to it.satellite })
            }
            assertNull(model.capabilitiesFor(receiverCount))
            assertNull(model.capabilitiesFor(-1))
        }

        val identities = Px4DeviceModel.entries.flatMapIndexed { index, model ->
            val base = "%014d".format(index + 1)
            val serial = if (model.bridgeCount == 2) null else "%015d".format(index + 100)
            if (model.bridgeCount == 2) {
                listOf(
                    identity("${model.adapterArgument}-1", "${base}1", model.productIds.first()),
                    identity("${model.adapterArgument}-2", "${base}2", model.productIds.first())
                )
            } else {
                listOf(identity(model.adapterArgument, serial!!, model.productIds.first()))
            }
        }
        val plan = Px4DeviceSelector.plan(identities)

        assertTrue(plan.rejections.isEmpty())
        assertEquals(Px4DeviceModel.entries.size, plan.enclosures.size)
        assertEquals(profileCases.values.sumOf { it.second.first }, plan.tuners.size)
        assertEquals(Px4DeviceModel.entries.toSet(), plan.enclosures.map { it.model }.toSet())
        plan.enclosures.forEach { enclosure ->
            assertEquals(enclosure.model.bridgeCount, enclosure.devices.size)
            assertEquals(enclosure.model.receiverCount,
                plan.tuners.count { it.instanceToken == enclosure.instanceToken })
        }
    }

    @Test
    fun twoBridgeProductsNeverPairAcrossDifferentProfiles() {
        val base = "12345678901234"
        val plan = Px4DeviceSelector.plan(listOf(
            identity("q3-u4", "${base}1", Px4DeviceSelector.Q3U4_PRODUCT_ID),
            identity("q3-pe4", "${base}2", Px4DeviceModel.Q3PE4.productIds.single())
        ))

        assertTrue(plan.enclosures.isEmpty())
        assertEquals(2, plan.rejections.size)
    }

    private fun identity(name: String, serial: String, productId: Int) =
        Px4DeviceIdentity(name, serial, productId)
}
