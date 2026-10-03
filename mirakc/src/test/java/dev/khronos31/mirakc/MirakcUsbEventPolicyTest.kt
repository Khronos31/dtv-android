package dev.khronos31.mirakc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirakcUsbEventPolicyTest {
    @Test
    fun tunerChangesRestartTheGeneratedUpstreamConfiguration() {
        assertTrue(MirakcUsbEventPolicy.affectsTunerConfiguration(0x3275, 0x0080))
        assertTrue(MirakcUsbEventPolicy.affectsTunerConfiguration(0x187f, 0x0302))
        assertTrue(MirakcUsbEventPolicy.affectsTunerConfiguration(0x0511, Px4DeviceSelector.Q3U4_PRODUCT_ID))
        assertTrue(MirakcUsbEventPolicy.affectsTunerConfiguration(0x0511, Px4DeviceSelector.M1UR_PRODUCT_ID))
        assertTrue(MirakcUsbEventPolicy.isPx4Tuner(0x0511, Px4DeviceSelector.S1UR_PRODUCT_ID))
    }

    @Test
    fun unrelatedUsbAndReaderChangesDoNotRestartMirakc() {
        assertFalse(MirakcUsbEventPolicy.affectsTunerConfiguration(0x04e6, 0x511a))
        assertFalse(MirakcUsbEventPolicy.affectsTunerConfiguration(0x0511, 0x7777))
        assertFalse(MirakcUsbEventPolicy.isPx4Tuner(0x0511, Px4DeviceSelector.MLT5_PRODUCT_IDS.first() + 1))
        assertFalse(MirakcUsbEventPolicy.affectsTunerConfiguration(0x1234, 0x5678))
    }
}
