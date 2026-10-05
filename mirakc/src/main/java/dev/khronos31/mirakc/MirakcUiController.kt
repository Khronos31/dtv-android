package dev.khronos31.mirakc

import dev.khronos31.mirakc.ui.contract.MirakcUiAction
import dev.khronos31.mirakc.ui.contract.MirakcUiState
import dev.khronos31.mirakc.ui.contract.RuntimePhase
import dev.khronos31.mirakc.ui.contract.UiCapabilities
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CopyOnWriteArraySet

/** The UI-facing port is deliberately independent of Android and mirakc classes. */
internal interface MirakcRuntimePort {
    fun snapshot(): MirakcUiState
    fun perform(action: MirakcUiAction): MirakcUiState
}

internal class MirakcUiOperationException(message: String) : RuntimeException(message)

/** Serializes UI commands, guards them against the current capabilities, and publishes snapshots. */
internal class MirakcUiController(
    private val runtime: MirakcRuntimePort,
    private val worker: Executor,
    initialState: MirakcUiState = runtime.snapshot()
) {
    private val lock = Any()
    private val deliveryLock = Any()
    private val observers = CopyOnWriteArraySet<(MirakcUiState) -> Unit>()
    private var sequence = 0L
    private var commandPending = false
    private var closed = false
    private var retainedError: String? = null
    private var currentState = initialState

    fun snapshot(): MirakcUiState = synchronized(lock) { currentState }

    fun attach(observer: (MirakcUiState) -> Unit): AutoCloseable {
        synchronized(deliveryLock) {
            val deliver = synchronized(lock) {
                if (closed) null else currentState.also { observers += observer }
            }
            deliver?.let { runCatching { observer(it) } }
        }
        return AutoCloseable { observers -= observer }
    }

    /** Terminally detach a Service-owned controller; late work becomes inert. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sequence++
            commandPending = false
        }
        synchronized(deliveryLock) { observers.clear() }
    }

    /** A later command invalidates any refresh that began against the older state. */
    fun refresh() {
        val capturedSequence = synchronized(lock) {
            if (closed) return
            sequence
        }
        try {
            worker.execute {
                if (synchronized(lock) { closed || capturedSequence != sequence }) return@execute
                val result = runCatching(runtime::snapshot).getOrNull() ?: return@execute
                val published = synchronized(lock) {
                    if (closed || capturedSequence != sequence || commandPending) return@synchronized null
                    val state = retainedError?.takeIf { result.message == null }?.let { message ->
                        result.copyForHost(
                            phase = RuntimePhase.ERROR,
                            statusTitle = "操作に失敗しました",
                            statusDetail = message,
                            message = message
                        )
                    } ?: result
                    currentState = state
                    state
                }
                published?.let(::publish)
            }
        } catch (_: RejectedExecutionException) {
            // Closing the host can race a poll that already passed its first
            // check. A rejected late poll has no user-visible consequence.
        }
    }

    fun dispatch(action: MirakcUiAction): Boolean {
        val operation: Long
        val optimistic: MirakcUiState
        val before: MirakcUiState
        synchronized(lock) {
            if (closed || commandPending || !actionAllowed(currentState.capabilities, action)) return false
            before = currentState
            commandPending = true
            operation = ++sequence
            retainedError = null
            optimistic = transitionState(currentState, action).copyForHost(
                capabilities = currentState.capabilities.disabledForCommand(),
                message = null
            )
            currentState = optimistic
        }
        publish(optimistic)
        try {
            worker.execute {
                if (synchronized(lock) { closed || operation != sequence }) return@execute
                var operationFailure: String? = null
                val updated = runCatching { runtime.perform(action) }.getOrElse { error ->
                    val message = (error as? MirakcUiOperationException)?.message ?: failureDetail(action)
                    operationFailure = message
                    before.copyForHost(
                        phase = RuntimePhase.ERROR,
                        statusTitle = "操作に失敗しました",
                        statusDetail = message,
                        capabilities = before.capabilities,
                        message = message
                    )
                }
                val published = synchronized(lock) {
                    if (closed || operation != sequence) return@synchronized null
                    commandPending = false
                    retainedError = operationFailure
                    currentState = updated
                    updated
                }
                published?.let(::publish)
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                if (operation == sequence) {
                    commandPending = false
                    currentState = before
                }
            }
            return false
        }
        return true
    }

    private fun publish(state: MirakcUiState) {
        synchronized(deliveryLock) {
            val current = synchronized(lock) { if (closed) null else currentState }
            if (state !== current) return
            observers.forEach { observer -> runCatching { observer(state) } }
        }
    }

    private fun actionAllowed(capabilities: UiCapabilities, action: MirakcUiAction): Boolean = when (action) {
        MirakcUiAction.StartScan -> capabilities.canScan
        MirakcUiAction.CancelScan -> capabilities.canCancelScan
        MirakcUiAction.StartServer -> capabilities.canStartServer
        MirakcUiAction.StopServer -> capabilities.canStopServer
        MirakcUiAction.RequestUsbPermission -> capabilities.canRequestUsbPermission
        MirakcUiAction.CheckUpdates -> capabilities.canCheckUpdates
        is MirakcUiAction.SaveEpgInterval, is MirakcUiAction.SaveChannels -> capabilities.canEditSettings
    }

    private fun transitionState(state: MirakcUiState, action: MirakcUiAction): MirakcUiState = when (action) {
        MirakcUiAction.StartScan -> state.copyForHost(
            phase = RuntimePhase.PREPARING,
            statusTitle = "チューナーを準備しています",
            statusDetail = "スキャンを開始しています",
            scan = null
        )
        MirakcUiAction.CancelScan -> state.copyForHost(
            phase = RuntimePhase.STOPPING,
            statusTitle = "スキャンを停止しています",
            statusDetail = "現在の探索を終了しています"
        )
        MirakcUiAction.StopServer -> state.copyForHost(
            phase = RuntimePhase.STOPPING,
            statusTitle = "mirakcを停止しています",
            statusDetail = "プロセスとUSB接続を終了しています"
        )
        MirakcUiAction.StartServer -> state.copyForHost(
            phase = RuntimePhase.STARTING_SERVER,
            statusTitle = "mirakcを起動しています",
            statusDetail = "mirakcの起動を確認しています"
        )
        MirakcUiAction.RequestUsbPermission -> state.copyForHost(
            phase = RuntimePhase.WAITING_FOR_PERMISSION,
            statusTitle = "USB権限を確認しています",
            statusDetail = "許可ダイアログを開いています"
        )
        MirakcUiAction.CheckUpdates,
        is MirakcUiAction.SaveEpgInterval,
        is MirakcUiAction.SaveChannels -> state
    }

    private fun failureDetail(action: MirakcUiAction): String = when (action) {
        MirakcUiAction.StartScan -> "チャンネルスキャンを開始できませんでした。準備状態を確認して再試行してください"
        MirakcUiAction.CancelScan -> "スキャンを停止できませんでした。状態を確認してください"
        MirakcUiAction.StartServer -> "mirakcを起動できませんでした。設定とUSB権限を確認してください"
        MirakcUiAction.StopServer -> "停止処理に失敗しました。状態を確認してください"
        MirakcUiAction.RequestUsbPermission -> "USB権限ダイアログを表示できませんでした"
        MirakcUiAction.CheckUpdates -> "アップデートを確認できませんでした"
        is MirakcUiAction.SaveEpgInterval -> "番組表の更新間隔を保存できませんでした"
        is MirakcUiAction.SaveChannels -> "チャンネル設定を保存できませんでした"
    }
}

private fun UiCapabilities.disabledForCommand() = copy(
    canScan = false,
    canCancelScan = false,
    canStartServer = false,
    canStopServer = false,
    canRequestUsbPermission = false,
    canEditSettings = false,
    canCheckUpdates = false
)

private fun MirakcUiState.copyForHost(
    phase: RuntimePhase = this.phase,
    statusTitle: String = this.statusTitle,
    statusDetail: String = this.statusDetail,
    scan: dev.khronos31.mirakc.ui.contract.ScanProgressUi? = this.scan,
    capabilities: UiCapabilities = this.capabilities,
    message: String? = this.message,
    updateSuccessNotice: dev.khronos31.mirakc.ui.contract.UpdateSuccessNotice? = this.updateSuccessNotice
) = MirakcUiState(
    phase = phase,
    statusTitle = statusTitle,
    statusDetail = statusDetail,
    devices = devices,
    scan = scan,
    prepared = prepared,
    capabilities = capabilities,
    settings = settings,
    about = about,
    message = message,
    updateSuccessNotice = updateSuccessNotice
)
