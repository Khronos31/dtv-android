package dev.khronos31.mirakc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SianoReaderAvailabilityTest {
    @Test
    fun failedOpenCanBeRetriedAfterUsbOrPermissionGenerationChanges() {
        val availability = SianoReaderAvailability()

        assertFalse(availability.unavailable)
        availability.markUnavailable()
        assertTrue(availability.unavailable)

        availability.resetForGenerationChange()

        assertFalse(availability.unavailable)
    }
}
