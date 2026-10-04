package dev.khronos31.mirakc

import org.junit.Assert.assertTrue
import org.junit.Test

class EpgUpdateScheduleRenderTest {
    @Test
    fun arbitraryMinuteIntervalIsRenderedAsFixedDelay() {
        val rendered = renderMirakcJobCommands("/native/arib", 7)
        assertTrue(rendered.contains("schedule: '@every 7m'"))
        assertTrue(rendered.contains("disabled: false"))
    }

    @Test
    fun maximumDayIntervalIsRenderedAsFixedDelay() {
        val rendered = renderMirakcJobCommands("/native/arib", 1440)
        assertTrue(rendered.contains("schedule: '@every 1440m'"))
    }
}
