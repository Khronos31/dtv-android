package dev.khronos31.mirakc

import dev.khronos31.mirakc.ui.contract.AboutUi
import dev.khronos31.mirakc.ui.contract.MirakcUiAction
import dev.khronos31.mirakc.ui.contract.MirakcUiState
import dev.khronos31.mirakc.ui.contract.PreparedChannelsUi
import dev.khronos31.mirakc.ui.contract.RuntimePhase
import dev.khronos31.mirakc.ui.contract.ScanPhase
import dev.khronos31.mirakc.ui.contract.ScanProgressUi
import dev.khronos31.mirakc.ui.contract.SettingsUi
import dev.khronos31.mirakc.ui.contract.TunerDeviceUi
import dev.khronos31.mirakc.ui.contract.UiCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class MirakcUiControllerTest {
    @Test
    fun scanActionLocksOutDuplicatesUntilPermissionWaitSnapshotArrives() {
        val waiting = state(
            phase = RuntimePhase.WAITING_FOR_PERMISSION,
            title = "USB permission",
            capabilities = UiCapabilities(canCancelScan = true)
        )
        val port = FakePort(state(), mapOf(MirakcUiAction.StartScan to waiting))
        val worker = QueuedExecutor()
        val controller = MirakcUiController(port, worker)

        assertTrue(controller.dispatch(MirakcUiAction.StartScan))
        assertEquals(RuntimePhase.PREPARING, controller.snapshot().phase)
        assertFalse(controller.dispatch(MirakcUiAction.StartScan))
        worker.runNext()

        assertEquals(RuntimePhase.WAITING_FOR_PERMISSION, controller.snapshot().phase)
        assertEquals("USB permission", controller.snapshot().statusTitle)
        assertEquals(1, port.actions.count { it == MirakcUiAction.StartScan })
    }

    @Test
    fun permissionGrantRefreshShowsActualScanProgressAndActivityCanReattach() {
        val waiting = state(
            phase = RuntimePhase.WAITING_FOR_PERMISSION,
            capabilities = UiCapabilities(canCancelScan = true)
        )
        val scanning = state(
            phase = RuntimePhase.SCANNING,
            scan = ScanProgressUi(91L, ScanPhase.RUNNING, 0, 50, 13, emptyList(), 0),
            capabilities = UiCapabilities(canCancelScan = true)
        )
        val port = FakePort(state(), mapOf(MirakcUiAction.StartScan to waiting))
        val worker = QueuedExecutor()
        val controller = MirakcUiController(port, worker)
        val firstActivity = mutableListOf<MirakcUiState>()
        val detach = controller.attach(firstActivity::add)

        assertTrue(controller.dispatch(MirakcUiAction.StartScan))
        worker.runNext()
        port.current = scanning // RuntimePort publishes this after USB grant continuation.
        controller.refresh()
        worker.runNext()
        detach.close()

        assertEquals(RuntimePhase.SCANNING, controller.snapshot().phase)
        assertEquals(13, controller.snapshot().scan?.currentChannel)
        val secondActivity = mutableListOf<MirakcUiState>()
        val secondDetach = controller.attach(secondActivity::add)
        assertEquals(RuntimePhase.SCANNING, secondActivity.single().phase)
        secondDetach.close()
    }

    @Test
    fun cancelStopsScanButKeepsPreviouslyPreparedChannels() {
        val prepared = PreparedChannelsUi(listOf(20, 21), 26, 12)
        val scanning = state(
            phase = RuntimePhase.SCANNING,
            scan = ScanProgressUi(92L, ScanPhase.RUNNING, 8, 50, 21, listOf(20), 0),
            prepared = prepared,
            capabilities = UiCapabilities(canCancelScan = true)
        )
        val canceled = state(
            phase = RuntimePhase.IDLE,
            title = "Canceled",
            scan = ScanProgressUi(92L, ScanPhase.CANCELED, 8, 50, null, listOf(20), 0),
            prepared = prepared,
            capabilities = UiCapabilities(canScan = true, canStartServer = true)
        )
        val port = FakePort(scanning, mapOf(MirakcUiAction.CancelScan to canceled))
        val worker = QueuedExecutor()
        val controller = MirakcUiController(port, worker)

        assertTrue(controller.dispatch(MirakcUiAction.CancelScan))
        assertEquals(RuntimePhase.STOPPING, controller.snapshot().phase)
        worker.runNext()

        assertEquals(ScanPhase.CANCELED, controller.snapshot().scan?.phase)
        assertEquals(listOf(20, 21), controller.snapshot().prepared?.terrestrialChannels)
        assertEquals(26, controller.snapshot().prepared?.satelliteBsChannels)
    }

    @Test
    fun preparationFailureAndSuccessfulSatelliteOnlySetupDoNotInventGrProgress() {
        val previous = PreparedChannelsUi(listOf(31), 26, 12)
        val failingPort = FakePort(state(prepared = previous), emptyMap())
        failingPort.failure = IllegalStateException("firmware preparation failed")
        val worker = QueuedExecutor()
        val failed = MirakcUiController(failingPort, worker)
        assertTrue(failed.dispatch(MirakcUiAction.StartScan))
        worker.runNext()
        assertEquals(RuntimePhase.ERROR, failed.snapshot().phase)
        assertEquals(listOf(31), failed.snapshot().prepared?.terrestrialChannels)

        val satelliteOnly = state(
            phase = RuntimePhase.IDLE,
            title = "No terrestrial tuner",
            prepared = PreparedChannelsUi(emptyList(), 26, 12),
            capabilities = UiCapabilities(canStartServer = true)
        )
        val port = FakePort(state(), mapOf(MirakcUiAction.StartScan to satelliteOnly))
        val satelliteWorker = QueuedExecutor()
        val controller = MirakcUiController(port, satelliteWorker)
        assertTrue(controller.dispatch(MirakcUiAction.StartScan))
        satelliteWorker.runNext()
        assertNull(controller.snapshot().scan)
        assertNotNull(controller.snapshot().prepared)
        assertTrue(controller.snapshot().prepared!!.terrestrialChannels.isEmpty())
    }

    @Test
    fun explicitStartAndStopKeepPreparationAcrossProcessRecoveryAndRejectStaleRefresh() {
        val prepared = PreparedChannelsUi(listOf(16, 17), 26, 12)
        val ready = state(
            phase = RuntimePhase.IDLE,
            prepared = prepared,
            capabilities = UiCapabilities(canStartServer = true, canEditSettings = true)
        )
        val running = state(
            phase = RuntimePhase.RUNNING_SERVER,
            title = "Server running",
            prepared = prepared,
            capabilities = UiCapabilities(canStopServer = true)
        )
        val stopped = ready
        val port = FakePort(
            ready,
            mapOf(MirakcUiAction.StartServer to running, MirakcUiAction.StopServer to stopped)
        )
        val worker = QueuedExecutor()
        val controller = MirakcUiController(port, worker)

        controller.refresh() // Enqueued old snapshot must not overwrite a later command.
        assertTrue(controller.dispatch(MirakcUiAction.StartServer))
        worker.runNext()
        worker.runNext()
        assertEquals(RuntimePhase.RUNNING_SERVER, controller.snapshot().phase)
        assertFalse(controller.dispatch(MirakcUiAction.StartServer))
        assertTrue(controller.dispatch(MirakcUiAction.StopServer))
        worker.runNext()
        assertEquals(RuntimePhase.IDLE, controller.snapshot().phase)

        val recoveredPort = FakePort(stopped, emptyMap()) // Persisted setup, requested-public-run is false.
        val recovered = MirakcUiController(recoveredPort, QueuedExecutor())
        assertEquals(RuntimePhase.IDLE, recovered.snapshot().phase)
        assertEquals(listOf(16, 17), recovered.snapshot().prepared?.terrestrialChannels)
        assertTrue(recovered.snapshot().capabilities.canStartServer)
        assertEquals(listOf(MirakcUiAction.StartServer, MirakcUiAction.StopServer), port.actions)
    }

    @Test
    fun closedControllerDoesNotRunQueuedCommandsOrRefreshes() {
        val port = FakePort(
            state(capabilities = UiCapabilities(canScan = true)),
            mapOf(MirakcUiAction.StartScan to state())
        )
        val worker = QueuedExecutor()
        val controller = MirakcUiController(port, worker)
        assertTrue(controller.dispatch(MirakcUiAction.StartScan))
        controller.close()
        controller.refresh()
        assertFalse(controller.dispatch(MirakcUiAction.StartScan))
        worker.runNext()
        assertTrue(port.actions.isEmpty())
    }

    @Test
    fun actionableErrorSurvivesRoutineRefreshUntilAnotherActionIsAccepted() {
        val editable = state(capabilities = UiCapabilities(canEditSettings = true))
        val port = FakePort(editable, emptyMap()).apply {
            failure = IllegalArgumentException("invalid interval")
        }
        val worker = QueuedExecutor()
        val controller = MirakcUiController(port, worker)

        assertTrue(controller.dispatch(MirakcUiAction.SaveEpgInterval("0")))
        worker.runNext()
        val error = controller.snapshot()
        assertEquals(RuntimePhase.ERROR, error.phase)
        assertEquals("番組表の更新間隔を保存できませんでした", error.message)

        controller.refresh()
        worker.runNext()
        assertEquals(error.message, controller.snapshot().message)
        assertEquals(error.statusDetail, controller.snapshot().statusDetail)
    }

    @Test
    fun acceptedScanClearsStaleProgressAndServerStopHasItsOwnTransition() {
        val prepared = PreparedChannelsUi(listOf(20), 26, 12)
        val staleScan = ScanProgressUi(93L, ScanPhase.CANCELED, 8, 50, null, listOf(20), 0)
        val ready = state(
            scan = staleScan,
            prepared = prepared,
            capabilities = UiCapabilities(canScan = true, canEditSettings = true)
        )
        val running = state(
            phase = RuntimePhase.RUNNING_SERVER,
            title = "mirakc running",
            prepared = prepared,
            capabilities = UiCapabilities(canStopServer = true)
        )
        val port = FakePort(ready, mapOf(MirakcUiAction.StartScan to ready))
        val worker = QueuedExecutor()
        val controller = MirakcUiController(port, worker)

        assertTrue(controller.dispatch(MirakcUiAction.StartScan))
        assertEquals("チューナーを準備しています", controller.snapshot().statusTitle)
        assertNull(controller.snapshot().scan)
        assertEquals(prepared, controller.snapshot().prepared)
        worker.runNext()

        val serverPort = FakePort(running, mapOf(MirakcUiAction.StopServer to ready))
        val serverWorker = QueuedExecutor()
        val serverController = MirakcUiController(serverPort, serverWorker)
        assertTrue(serverController.dispatch(MirakcUiAction.StopServer))
        assertEquals("mirakcを停止しています", serverController.snapshot().statusTitle)
    }

    private class FakePort(
        var current: MirakcUiState,
        private val results: Map<MirakcUiAction, MirakcUiState>
    ) : MirakcRuntimePort {
        val actions = mutableListOf<MirakcUiAction>()
        var failure: RuntimeException? = null

        override fun snapshot(): MirakcUiState = current

        override fun perform(action: MirakcUiAction): MirakcUiState {
            actions += action
            failure?.let { throw it }
            return (results[action] ?: current).also { current = it }
        }
    }

    private class QueuedExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runNext() = queue.removeFirst().run()
    }

    private fun state(
        phase: RuntimePhase = RuntimePhase.IDLE,
        title: String = "Status",
        scan: ScanProgressUi? = null,
        prepared: PreparedChannelsUi? = null,
        capabilities: UiCapabilities = UiCapabilities(canScan = true),
        message: String? = null
    ) = MirakcUiState(
        phase = phase,
        statusTitle = title,
        statusDetail = "",
        devices = listOf(TunerDeviceUi("px4", "PX4", 8, true, true, true)),
        scan = scan,
        prepared = prepared,
        capabilities = capabilities,
        settings = SettingsUi("", 10),
        about = AboutUi("1.0.0", emptyList(), emptyList(), "https://github.com/example/app", "license"),
        message = message
    )

}
