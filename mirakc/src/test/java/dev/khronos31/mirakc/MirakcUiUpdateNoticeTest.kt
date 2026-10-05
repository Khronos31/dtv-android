package dev.khronos31.mirakc

import dev.khronos31.mirakc.ui.contract.AboutUi
import dev.khronos31.mirakc.ui.contract.MirakcUiAction
import dev.khronos31.mirakc.ui.contract.MirakcUiState
import dev.khronos31.mirakc.ui.contract.RuntimePhase
import dev.khronos31.mirakc.ui.contract.SettingsUi
import dev.khronos31.mirakc.ui.contract.UiCapabilities
import dev.khronos31.mirakc.ui.contract.UpdateSuccessNotice
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirakcUiUpdateNoticeTest {
    @Test
    fun checkUpdatesOptimisticAndRefreshCopiesKeepNoticeAndNoBanner() {
        val notice = UpdateSuccessNotice(eventId = 19L, text = "アプリは最新です")
        val initial = MirakcUiState(
            phase = RuntimePhase.IDLE,
            statusTitle = "Ready",
            statusDetail = "",
            devices = emptyList(),
            scan = null,
            prepared = null,
            capabilities = UiCapabilities(canCheckUpdates = true),
            settings = SettingsUi("", 60),
            about = AboutUi("1.0", emptyList(), emptyList(), "https://github.com/example/app", ""),
            updateSuccessNotice = notice
        )
        val runtime = object : MirakcRuntimePort {
            override fun snapshot() = initial
            override fun perform(action: MirakcUiAction) = initial
        }
        val controller = MirakcUiController(runtime, Executor { it.run() })
        val published = mutableListOf<MirakcUiState>()
        controller.attach(published::add)

        assertTrue(controller.dispatch(MirakcUiAction.CheckUpdates))

        assertTrue(published.isNotEmpty())
        assertTrue(published.all { it.updateSuccessNotice == notice })
        assertTrue(published.all { it.message == null })
        assertFalse(controller.snapshot().message == notice.text)
        assertEquals(notice, controller.snapshot().updateSuccessNotice)
        controller.close()
    }
}
