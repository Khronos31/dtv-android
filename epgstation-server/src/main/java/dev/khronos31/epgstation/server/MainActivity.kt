package dev.khronos31.epgstation.server

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.khronos31.epgstation.ui.EpgStationTvScreen
import dev.khronos31.epgstation.ui.contract.BaseUrlValidation
import dev.khronos31.epgstation.ui.contract.EpgStationBaseUrlPolicy
import dev.khronos31.epgstation.ui.contract.EpgStationAboutUi
import dev.khronos31.epgstation.ui.contract.EpgStationUiAction
import dev.khronos31.epgstation.ui.contract.EpgStationUiState
import dev.khronos31.epgstation.ui.contract.LicenseDocumentUi
import dev.khronos31.epgstation.ui.contract.QrImageUi
import dev.khronos31.epgstation.ui.contract.RepositoryLinkUi
import dev.khronos31.epgstation.ui.contract.RecordingVolumeUi
import dev.khronos31.epgstation.ui.contract.UpdatePromptUi
import dev.khronos31.epgstation.ui.contract.UpdateSuccessNoticeUi
import dev.khronos31.updater.GitHubReleaseUpdater
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val updater by lazy { GitHubReleaseUpdater(this, "epgstation-server", "dev.khronos31.epgstation.server") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var preferences: SharedPreferences
    private var uiState: EpgStationUiState? by mutableStateOf(null)
    private var updateBusy = false
    private var updatePrompt: UpdatePromptUi? = null
    private var updateSuccessNotice: UpdateSuccessNoticeUi? = null
    private var nextUpdateEventId = 1L
    private var baseUrlSaveRevision = 0L
    private var pendingUpdate: GitHubReleaseUpdater.AvailableUpdate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        refreshPresentationState()
        setContent {
            uiState?.let { state -> EpgStationTvScreen(state, ::dispatch) }
        }
        startServerService()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::preferences.isInitialized) refreshPresentationState()
    }

    private fun dispatch(action: EpgStationUiAction) {
        when (action) {
            is EpgStationUiAction.SaveBaseUrl -> saveBaseUrl(action.input)
            EpgStationUiAction.CancelBaseUrlEdit -> {
                updateValidationError(null)
            }
            is EpgStationUiAction.SelectStorage -> selectStorage(action.id)
            EpgStationUiAction.CheckUpdates -> checkForUpdate()
            EpgStationUiAction.ConfirmUpdateDownload -> downloadUpdate()
            EpgStationUiAction.CancelUpdatePrompt -> {
                updatePrompt = null
                pendingUpdate = null
                refreshPresentationState()
            }
            EpgStationUiAction.OpenUnknownSourcesSettings -> {
                updatePrompt = null
                refreshPresentationState()
                updater.openUnknownSourcesSettings(this)
            }
            EpgStationUiAction.OpenAbout -> openAbout()
            EpgStationUiAction.CloseAbout -> updateUiState { copy(aboutVisible = false, selectedLicenseId = null) }
            is EpgStationUiAction.OpenLicense -> updateUiState {
                if (about?.licenses?.any { it.id == action.id } == true) copy(selectedLicenseId = action.id) else this
            }
            EpgStationUiAction.CloseLicense -> updateUiState { copy(selectedLicenseId = null) }
        }
    }

    private fun openAbout() {
        updateUiState { copy(aboutVisible = true) }
        val state = uiState ?: return
        if (state.about == null && !state.aboutLoading) loadAboutInBackground()
    }

    private fun loadAboutInBackground() {
        updateUiState { copy(aboutLoading = true) }
        val appVersion = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
        Thread({
            val about = loadAboutInfo(appVersion)
            mainHandler.post {
                if (!isFinishing && !isDestroyed) {
                    updateUiState { copy(about = about, aboutLoading = false) }
                }
            }
        }, "epgstation-about-loader").apply { isDaemon = true }.start()
    }

    private fun loadAboutInfo(appVersion: String): EpgStationAboutUi {
        val epgStationVersion = runCatching {
            JSONObject(assets.open("package.json").bufferedReader().use { it.readText() })
                .optString("version")
                .takeIf(String::isNotBlank)
        }.getOrNull() ?: runCatching {
            assets.open(".versions").bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("EPGStation v") }
                    ?.removePrefix("EPGStation v")
                    ?.takeIf(String::isNotBlank)
            }
        }.getOrNull() ?: "unknown"

        val licenseSpecs = listOf(
            "dtv-android-LICENSE" to "dtv-android (Apache-2.0)",
            "EPGStation-LICENSE" to "EPGStation",
            "Node-LICENSE" to "Node.js mobile / Node.js",
            "NOTICE.npm.txt" to "npm dependency license list",
            "FFmpeg-LGPL-2.1-COPYING" to "FFmpeg (LGPL-2.1-or-later)",
            "OpenH264-LICENSE" to "OpenH264 (BSD-2-Clause)",
            "Android-NDK-NOTICE" to "Android NDK notice",
            "Android-NDK-TOOLCHAIN-NOTICE" to "Android NDK toolchain notice"
        )
        val licenses = mutableListOf<LicenseDocumentUi>()
        val missing = mutableListOf<String>()
        for ((fileName, title) in licenseSpecs) {
            val body = runCatching {
                assets.open("licenses/$fileName").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (body == null) missing += fileName else licenses += LicenseDocumentUi(fileName, title, body)
        }
        return EpgStationAboutUi(
            appVersion = appVersion,
            epgStationVersion = epgStationVersion,
            repositories = listOf(
                RepositoryLinkUi("アプリ", APP_REPOSITORY_URL),
                RepositoryLinkUi("EPGStation", EPGSTATION_REPOSITORY_URL)
            ),
            licenses = licenses,
            missingLicenseDocuments = missing,
            licenseCoverageNote = "このAPKに同梱されているライセンス文書とNOTICEです。"
        )
    }

    private fun saveBaseUrl(input: String) {
        when (val validation = EpgStationBaseUrlPolicy.validate(input)) {
            BaseUrlValidation.Invalid -> updateValidationError(EpgStationBaseUrlPolicy.ERROR)
            is BaseUrlValidation.Valid -> {
                preferences.edit().putString(KEY_MIRAKURUN_URL, validation.normalized).apply()
                baseUrlSaveRevision += 1
                refreshPresentationState(baseUrl = validation.normalized, validationError = null)
                startServerService(restart = true)
            }
        }
    }

    private fun selectStorage(id: String) {
        val volumes = RecordingStorage.list(this)
        val volume = volumes.firstOrNull { it.id == id } ?: return
        if (!volume.available) return
        RecordingStorage.save(this, volume.id)
        refreshPresentationState()
        startServerService(restart = true)
    }

    private fun checkForUpdate() {
        if (updateBusy) return
        updateBusy = true
        updatePrompt = null
        refreshPresentationState()
        updater.check { result ->
            updateBusy = false
            when (result) {
                is GitHubReleaseUpdater.CheckResult.UpToDate -> {
                    updatePrompt = null
                    updateSuccessNotice = UpdateSuccessNoticeUi(
                        eventId = nextUpdateEventId++,
                        text = "アプリは最新です (${result.installedVersion})"
                    )
                }
                is GitHubReleaseUpdater.CheckResult.Failure -> updatePrompt = UpdatePromptUi.Failure(result.message)
                is GitHubReleaseUpdater.CheckResult.UpdateAvailable -> {
                    pendingUpdate = result.update
                    updatePrompt = UpdatePromptUi.Available(result.update.versionText)
                }
            }
            refreshPresentationState()
        }
    }

    private fun downloadUpdate() {
        if (updateBusy) return
        val update = pendingUpdate ?: return
        updatePrompt = null
        updateBusy = true
        refreshPresentationState()
        updater.downloadAndInstall(update, this) { result ->
            updateBusy = false
            when (result) {
                GitHubReleaseUpdater.DownloadResult.InstallerLaunched -> {
                    updatePrompt = null
                    pendingUpdate = null
                }
                GitHubReleaseUpdater.DownloadResult.NeedUnknownSourcesPermission ->
                    updatePrompt = UpdatePromptUi.UnknownSourcesPermission
                is GitHubReleaseUpdater.DownloadResult.Failure ->
                    updatePrompt = UpdatePromptUi.DownloadFailure(result.message)
            }
            refreshPresentationState()
        }
    }

    private fun startServerService(restart: Boolean = false) {
        val intent = Intent(this, EpgStationService::class.java)
        if (restart) intent.action = EpgStationService.ACTION_RESTART
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun refreshPresentationState(
        baseUrl: String = preferences.getString(KEY_MIRAKURUN_URL, DEFAULT_MIRAKURUN_URL) ?: DEFAULT_MIRAKURUN_URL,
        validationError: String? = uiState?.baseUrlValidationError
    ) {
        val urls = LanQr.listenUrls(EpgStationService.PORT)
        val bitmap = LanQr.bitmap(urls.first(), 440)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val qrImage = QrImageUi(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()

        val volumes = RecordingStorage.list(this)
        val selected = RecordingStorage.selected(this)
        uiState = EpgStationUiState(
            listenUrls = urls,
            qrImage = qrImage,
            baseUrl = baseUrl,
            baseUrlSaveRevision = baseUrlSaveRevision,
            baseUrlValidationError = validationError,
            volumes = volumes.map { volume ->
                RecordingVolumeUi(
                    id = volume.id,
                    title = volume.title,
                    detail = volume.detail,
                    available = volume.available || volume.id == RecordingStorage.INTERNAL_ID,
                    removable = volume.removable,
                    selected = volume.id == selected.id,
                    recordedPath = volume.recordedDir.absolutePath,
                    freeBytes = volume.freeBytes,
                    totalBytes = volume.totalBytes
                )
            },
            updateBusy = updateBusy,
            updatePrompt = updatePrompt,
            updateSuccessNotice = updateSuccessNotice,
            about = uiState?.about,
            aboutLoading = uiState?.aboutLoading ?: false,
            aboutVisible = uiState?.aboutVisible ?: false,
            selectedLicenseId = uiState?.selectedLicenseId
        )
    }

    private fun updateValidationError(error: String?) {
        val current = uiState ?: return
        uiState = current.copy(baseUrlValidationError = error)
    }

    private inline fun updateUiState(transform: EpgStationUiState.() -> EpgStationUiState) {
        uiState = uiState?.transform()
    }

    companion object {
        const val PREFERENCES = "epgstation-server"
        const val KEY_MIRAKURUN_URL = "mirakurun_url"
        const val KEY_RECORDED_VOLUME = "recorded_volume"
        const val DEFAULT_MIRAKURUN_URL = "http://127.0.0.1:40772/"
        const val APP_REPOSITORY_URL = "https://github.com/Khronos31/dtv-android"
        const val EPGSTATION_REPOSITORY_URL = "https://github.com/l3tnun/EPGStation"
    }
}
