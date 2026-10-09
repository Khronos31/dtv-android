package dev.khronos31.epgstation.ui.contract

import java.net.URI
import java.util.Collections

enum class StorageAccessUi {
    None,
    NeedsAllFilesAccess,
    NeedsProcessRestart,
}

data class RecordingVolumeUi(
    val id: String,
    val title: String,
    val detail: String,
    val available: Boolean,
    val removable: Boolean,
    val selected: Boolean,
    val recordedPath: String,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val storageAccess: StorageAccessUi = StorageAccessUi.None,
)

/** Immutable raster matching the first URL in [EpgStationUiState.listenUrls]. */
class QrImageUi(val width: Int, val height: Int, argbPixels: IntArray) {
    private val pixels = argbPixels.clone()

    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    fun copyPixels(): IntArray = pixels.clone()
}

sealed interface UpdatePromptUi {
    data class Available(val versionText: String) : UpdatePromptUi
    data class Failure(val message: String) : UpdatePromptUi
    data object UnknownSourcesPermission : UpdatePromptUi
    data class DownloadFailure(val message: String) : UpdatePromptUi
}

data class UpdateSuccessNoticeUi(val eventId: Long, val text: String)

data class RepositoryLinkUi(val label: String, val url: String)

data class LicenseDocumentUi(val id: String, val title: String, val body: String)

class EpgStationAboutUi(
    val appVersion: String,
    val epgStationVersion: String,
    repositories: Collection<RepositoryLinkUi>,
    licenses: Collection<LicenseDocumentUi>,
    missingLicenseDocuments: Collection<String> = emptyList(),
    val licenseCoverageNote: String = ""
) {
    val repositories: List<RepositoryLinkUi> = immutableCopy(repositories)
    val licenses: List<LicenseDocumentUi> = immutableCopy(licenses)
    val missingLicenseDocuments: List<String> = immutableCopy(missingLicenseDocuments)
}

class EpgStationUiState(
    listenUrls: Collection<String>,
    val qrImage: QrImageUi?,
    val baseUrl: String,
    val baseUrlSaveRevision: Long,
    val baseUrlValidationError: String?,
    volumes: Collection<RecordingVolumeUi>,
    val updateBusy: Boolean,
    val updatePrompt: UpdatePromptUi?,
    val updateSuccessNotice: UpdateSuccessNoticeUi?,
    val about: EpgStationAboutUi? = null,
    val aboutLoading: Boolean = false,
    val aboutVisible: Boolean = false,
    val selectedLicenseId: String? = null
) {
    val listenUrls: List<String> = immutableCopy(listenUrls)
    val volumes: List<RecordingVolumeUi> = immutableCopy(volumes)

    fun copy(
        listenUrls: Collection<String> = this.listenUrls,
        qrImage: QrImageUi? = this.qrImage,
        baseUrl: String = this.baseUrl,
        baseUrlSaveRevision: Long = this.baseUrlSaveRevision,
        baseUrlValidationError: String? = this.baseUrlValidationError,
        volumes: Collection<RecordingVolumeUi> = this.volumes,
        updateBusy: Boolean = this.updateBusy,
        updatePrompt: UpdatePromptUi? = this.updatePrompt,
        updateSuccessNotice: UpdateSuccessNoticeUi? = this.updateSuccessNotice,
        about: EpgStationAboutUi? = this.about,
        aboutLoading: Boolean = this.aboutLoading,
        aboutVisible: Boolean = this.aboutVisible,
        selectedLicenseId: String? = this.selectedLicenseId
    ) = EpgStationUiState(
        listenUrls, qrImage, baseUrl, baseUrlSaveRevision, baseUrlValidationError,
        volumes, updateBusy, updatePrompt, updateSuccessNotice, about,
        aboutLoading, aboutVisible, selectedLicenseId
    )
}

sealed interface EpgStationUiAction {
    data class SaveBaseUrl(val input: String) : EpgStationUiAction
    data object CancelBaseUrlEdit : EpgStationUiAction
    data class SelectStorage(val id: String) : EpgStationUiAction
    data object CheckUpdates : EpgStationUiAction
    data object ConfirmUpdateDownload : EpgStationUiAction
    data object CancelUpdatePrompt : EpgStationUiAction
    data object OpenUnknownSourcesSettings : EpgStationUiAction
    data object OpenAbout : EpgStationUiAction
    data object CloseAbout : EpgStationUiAction
    data class OpenLicense(val id: String) : EpgStationUiAction
    data object CloseLicense : EpgStationUiAction
}

sealed interface BaseUrlValidation {
    data class Valid(val normalized: String) : BaseUrlValidation
    data object Invalid : BaseUrlValidation
}

object EpgStationBaseUrlPolicy {
    const val ERROR = "Enter an absolute http:// or https:// URL"

    fun validate(input: String): BaseUrlValidation {
        val value = input.trim()
        val parsed = runCatching { URI(value) }.getOrNull()
        if (parsed == null || parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            return BaseUrlValidation.Invalid
        }
        return BaseUrlValidation.Valid(if (value.endsWith('/')) value else "$value/")
    }
}

private fun <T> immutableCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
