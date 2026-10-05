package dev.khronos31.mirakc.ui.contract

import java.util.Collections

enum class RuntimePhase {
    IDLE,
    WAITING_FOR_PERMISSION,
    PREPARING,
    STARTING_SCAN,
    SCANNING,
    FINISHING_SCAN,
    STARTING_SERVER,
    RUNNING_SERVER,
    STOPPING,
    ERROR
}

enum class ScanPhase { WAITING, RUNNING, COMPLETE, EMPTY, CANCELED, FAILED }

data class TunerDeviceUi(
    val id: String,
    val displayName: String,
    val receiverCount: Int,
    val supportsTerrestrial: Boolean,
    val supportsSatellite: Boolean,
    val permissionGranted: Boolean
)

class ScanProgressUi(
    val operationId: Long,
    val phase: ScanPhase,
    val completed: Int,
    val total: Int,
    val currentChannel: Int?,
    foundChannels: Collection<Int>,
    val failedChannels: Int
) {
    val foundChannels: List<Int> = immutableCopy(foundChannels)
}

class PreparedChannelsUi(
    terrestrialChannels: Collection<Int>,
    val satelliteBsChannels: Int,
    val satelliteCsChannels: Int
) {
    val terrestrialChannels: List<Int> = immutableCopy(terrestrialChannels)
}

data class UiCapabilities(
    val canScan: Boolean = false,
    val canCancelScan: Boolean = false,
    val canStartServer: Boolean = false,
    val canStopServer: Boolean = false,
    val canRequestUsbPermission: Boolean = false,
    val canEditSettings: Boolean = false,
    val canCheckUpdates: Boolean = false
)

data class SettingsUi(
    val terrestrialInput: String,
    val epgIntervalMinutes: Int,
    val epgIntervalMinimum: Int = 1,
    val epgIntervalMaximum: Int = 1440
)

data class VersionRowUi(val label: String, val value: String)

data class UpdateSuccessNotice(val eventId: Long, val text: String)

class AboutUi(
    val appVersion: String,
    engineVersions: Collection<VersionRowUi>,
    driverVersions: Collection<VersionRowUi>,
    val repositoryUrl: String,
    val licenseText: String
) {
    val engineVersions: List<VersionRowUi> = immutableCopy(engineVersions)
    val driverVersions: List<VersionRowUi> = immutableCopy(driverVersions)
}

class MirakcUiState(
    val phase: RuntimePhase,
    val statusTitle: String,
    val statusDetail: String,
    devices: Collection<TunerDeviceUi>,
    val scan: ScanProgressUi?,
    val prepared: PreparedChannelsUi?,
    val capabilities: UiCapabilities,
    val settings: SettingsUi,
    val about: AboutUi,
    val message: String? = null,
    val updateSuccessNotice: UpdateSuccessNotice? = null
) {
    val devices: List<TunerDeviceUi> = immutableCopy(devices)
}

sealed interface MirakcUiAction {
    data object StartScan : MirakcUiAction
    data object CancelScan : MirakcUiAction
    data object StartServer : MirakcUiAction
    data object StopServer : MirakcUiAction
    data object RequestUsbPermission : MirakcUiAction
    data object CheckUpdates : MirakcUiAction
    data class SaveEpgInterval(val input: String) : MirakcUiAction
    data class SaveChannels(val input: String) : MirakcUiAction
}

private fun <T> immutableCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
