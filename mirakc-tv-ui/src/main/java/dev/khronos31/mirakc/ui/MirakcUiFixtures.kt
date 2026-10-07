package dev.khronos31.mirakc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.khronos31.mirakc.ui.contract.AboutUi
import dev.khronos31.mirakc.ui.contract.MirakcUiState
import dev.khronos31.mirakc.ui.contract.PreparedChannelsUi
import dev.khronos31.mirakc.ui.contract.RuntimePhase
import dev.khronos31.mirakc.ui.contract.ScanPhase
import dev.khronos31.mirakc.ui.contract.ScanProgressUi
import dev.khronos31.mirakc.ui.contract.SettingsUi
import dev.khronos31.mirakc.ui.contract.TunerDeviceUi
import dev.khronos31.mirakc.ui.contract.UiCapabilities
import dev.khronos31.mirakc.ui.contract.VersionRowUi

/** Stable preview/sample snapshots; all collections are copied by the contract DTOs. */
object MirakcUiFixtures {
    private val q3 = TunerDeviceUi("q3u4-1", "PX-Q3U4", 8, true, true, true)
    private val about = AboutUi(
        appVersion = "0.4.0",
        engineVersions = listOf(VersionRowUi("mirakc", "3.4.88")),
        driverVersions = listOf(VersionRowUi("PX4 userland", "host supplied")),
        repositoryUrl = "https://github.com/Khronos31/dtv-android",
        licenseText = "Open-source licenses are shown here in the host-provided text."
    )

    private fun state(
        phase: RuntimePhase,
        title: String,
        detail: String,
        scan: ScanProgressUi? = null,
        prepared: PreparedChannelsUi? = null,
        capabilities: UiCapabilities = UiCapabilities(),
        devices: List<TunerDeviceUi> = listOf(q3),
        message: String? = null
    ) = MirakcUiState(
        phase = phase,
        statusTitle = title,
        statusDetail = detail,
        devices = devices,
        scan = scan,
        prepared = prepared,
        capabilities = capabilities,
        settings = SettingsUi("16,21-27", 60),
        about = about,
        message = message
    )

    val Unconfigured = state(
        RuntimePhase.IDLE,
        "セットアップが必要です",
        "チャンネルスキャンから準備を始めます。",
        capabilities = UiCapabilities(canScan = true, canRequestUsbPermission = true)
    )

    val PermissionWait = state(
        RuntimePhase.WAITING_FOR_PERMISSION,
        "USB権限を待っています",
        "チューナーの権限を許可すると準備を続けます。",
        capabilities = UiCapabilities(canRequestUsbPermission = true),
        devices = listOf(q3.copy(permissionGranted = false))
    )

    val Preparing = state(
        RuntimePhase.PREPARING,
        "チューナーを準備しています",
        "接続デバイス、ドライバー、PX4ファームウェアを確認しています。"
    )

    val ScanWaiting = state(
        RuntimePhase.STARTING_SCAN,
        "スキャンを待っています",
        "チューナーが空くと探索を開始します。",
        ScanProgressUi(101, ScanPhase.WAITING, 0, 50, null, emptyList(), 0),
        capabilities = UiCapabilities(canCancelScan = true)
    )

    val Scanning = state(
        RuntimePhase.SCANNING,
        "チャンネルを探索中です",
        "実際の探索結果を表示しています。",
        ScanProgressUi(102, ScanPhase.RUNNING, 17, 50, 30, listOf(16, 21, 22), 1),
        capabilities = UiCapabilities(canCancelScan = true)
    )

    val ScanFinished = state(
        RuntimePhase.IDLE,
        "スキャンが完了しました",
        "結果を保存しました。",
        ScanProgressUi(103, ScanPhase.COMPLETE, 50, 50, null, listOf(16, 21, 22, 23), 0),
        PreparedChannelsUi(listOf(16, 21, 22, 23), 0, 0),
        UiCapabilities(canStartServer = true, canScan = true, canEditSettings = true)
    )

    val NoTerrestrialWithSatellite = state(
        RuntimePhase.IDLE,
        "BS/CSを利用できます",
        "地デジ対応チューナーがありません。BS/CSの設定を準備しました。",
        scan = null,
        prepared = PreparedChannelsUi(emptyList(), 26, 12),
        capabilities = UiCapabilities(canStartServer = true, canEditSettings = true),
        devices = listOf(TunerDeviceUi("satellite-only", "Satellite tuner", 1, false, true, true))
    )

    val Failed = state(
        RuntimePhase.ERROR,
        "スキャンに失敗しました",
        "既存のチャンネル設定は維持されています。",
        ScanProgressUi(105, ScanPhase.FAILED, 12, 50, 24, listOf(16), 3),
        prepared = PreparedChannelsUi(listOf(16, 21, 22), 26, 12),
        capabilities = UiCapabilities(canScan = true, canStartServer = true, canEditSettings = true),
        message = "USB tuner became unavailable"
    )

    val Starting = state(
        RuntimePhase.STARTING_SERVER,
        "mirakcを起動しています",
        "公開APIの起動を確認しています。",
        prepared = PreparedChannelsUi(listOf(16, 21, 22), 13, 12)
    )

    val Running = state(
        RuntimePhase.RUNNING_SERVER,
        "mirakcが動作中です",
        "HTTP API: 0.0.0.0:40772",
        prepared = PreparedChannelsUi(listOf(16, 21, 22), 13, 12),
        capabilities = UiCapabilities(canStopServer = true)
    )

    val Stopping = state(
        RuntimePhase.STOPPING,
        "mirakcを停止しています",
        "プロセスとチューナーを解放しています。"
    )
}

@Preview(name = "Unconfigured")
@Composable
private fun UnconfiguredPreview() {
    MirakcTvScreen(MirakcUiFixtures.Unconfigured) { }
}

@Preview(name = "PermissionWait")
@Composable
private fun PermissionWaitPreview() {
    MirakcTvScreen(MirakcUiFixtures.PermissionWait) { }
}

@Preview(name = "Preparing")
@Composable
private fun PreparingPreview() {
    MirakcTvScreen(MirakcUiFixtures.Preparing) { }
}

@Preview(name = "ScanWaiting")
@Composable
private fun ScanWaitingPreview() {
    MirakcTvScreen(MirakcUiFixtures.ScanWaiting) { }
}

@Preview(name = "Scanning")
@Composable
private fun ScanningPreview() {
    MirakcTvScreen(MirakcUiFixtures.Scanning) { }
}

@Preview(name = "ScanFinished")
@Composable
private fun ScanFinishedPreview() {
    MirakcTvScreen(MirakcUiFixtures.ScanFinished) { }
}

@Preview(name = "NoTerrestrialWithSatellite")
@Composable
private fun NoTerrestrialWithSatellitePreview() {
    MirakcTvScreen(MirakcUiFixtures.NoTerrestrialWithSatellite) { }
}

@Preview(name = "Failed")
@Composable
private fun FailedPreview() {
    MirakcTvScreen(MirakcUiFixtures.Failed) { }
}

@Preview(name = "Starting")
@Composable
private fun StartingPreview() {
    MirakcTvScreen(MirakcUiFixtures.Starting) { }
}

@Preview(name = "Running")
@Composable
private fun RunningPreview() {
    MirakcTvScreen(MirakcUiFixtures.Running) { }
}

@Preview(name = "Stopping")
@Composable
private fun StoppingPreview() {
    MirakcTvScreen(MirakcUiFixtures.Stopping) { }
}

