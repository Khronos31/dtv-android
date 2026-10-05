package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Px4InventoryProjectionTest {
    @Test
    fun unpermittedDetectedDeviceRemainsVisibleBesideMappedQ3Enclosure() {
        val permittedQ3Pair = listOf(
            Px4DeviceIdentity("q3-1", "123456789012341", Px4DeviceSelector.Q3U4_PRODUCT_ID),
            Px4DeviceIdentity("q3-2", "123456789012342", Px4DeviceSelector.Q3U4_PRODUCT_ID)
        )
        val mappedNames = Px4DeviceSelector.plan(permittedQ3Pair).enclosures
            .flatMap { it.devices }
            .mapTo(hashSetOf()) { it.deviceName }
        val detected = permittedQ3Pair.map { it.deviceName to it.productId } +
            ("s1ur-unpermitted" to Px4DeviceSelector.S1UR_PRODUCT_ID)

        val visibleUnmapped = Px4DeviceSelector.unmappedDetectedDevices(detected, mappedNames)

        assertEquals(1, visibleUnmapped.size)
        assertEquals("s1ur-unpermitted", visibleUnmapped.single().deviceName)
        assertEquals(Px4DeviceModel.S1UR, visibleUnmapped.single().model)
        assertTrue(mappedNames.containsAll(permittedQ3Pair.map { it.deviceName }))
    }
}
