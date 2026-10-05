package dev.khronos31.epgstation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.khronos31.epgstation.ui.contract.EpgStationAboutUi
import dev.khronos31.epgstation.ui.contract.EpgStationBaseUrlPolicy
import dev.khronos31.epgstation.ui.contract.EpgStationUiState
import dev.khronos31.epgstation.ui.contract.LicenseDocumentUi
import dev.khronos31.epgstation.ui.contract.QrImageUi
import dev.khronos31.epgstation.ui.contract.RecordingVolumeUi
import dev.khronos31.epgstation.ui.contract.RepositoryLinkUi
import dev.khronos31.epgstation.ui.contract.UpdatePromptUi

/**
 * Stable preview and test snapshots for EPGStation Server TV UI.
 */
object EpgStationUiFixtures {
    // Generate a simple 21x21 checkerboard QR-like pixel pattern for preview
    private val sampleQrPixels = IntArray(21 * 21) { index ->
        val x = index % 21
        val y = index / 21
        val isBorder = x == 0 || x == 20 || y == 0 || y == 20
        val isFinder1 = (x in 2..6 && (y in 2..6)) && (x == 2 || x == 6 || y == 2 || y == 6 || (x == 4 && y == 4))
        val isFinder2 = (x in 14..18 && (y in 2..6)) && (x == 14 || x == 18 || y == 2 || y == 6 || (x == 16 && y == 4))
        val isFinder3 = (x in 2..6 && (y in 14..18)) && (x == 2 || x == 6 || y == 2 || y == 6 || (x == 4 && y == 16))
        val isPattern = (x + y) % 3 == 0
        if (isBorder || isFinder1 || isFinder2 || isFinder3 || isPattern) {
            -0x1000000 // Black ARGB
        } else {
            -0x1 // White ARGB
        }
    }

    private val sampleQr = QrImageUi(21, 21, sampleQrPixels)

    val singleVolume = listOf(
        RecordingVolumeUi(
            id = "internal",
            title = "Internal storage",
            detail = "18.7 GB free",
            available = true,
            removable = false,
            selected = true,
            recordedPath = "/data/user/0/dev.khronos31.epgstation.server/files/epgstation/recorded",
            freeBytes = 20_080_295_936L, // 18.7 GB
            totalBytes = 26_306_674_688L  // 24.5 GB
        )
    )

    val sampleVolumes = listOf(
        RecordingVolumeUi(
            id = "internal",
            title = "Internal storage",
            detail = "32 GB free",
            available = true,
            removable = false,
            selected = true,
            recordedPath = "/data/user/0/dev.khronos31.epgstation.server/files/epgstation/recorded",
            freeBytes = 34_359_738_368L, // 32.0 GB
            totalBytes = 68_719_476_736L  // 64.0 GB
        ),
        RecordingVolumeUi(
            id = "usb-sda1",
            title = "External Drive",
            detail = "1.5 TB free",
            available = true,
            removable = true,
            selected = false,
            recordedPath = "/storage/1234-5678/Android/data/dev.khronos31.epgstation.server/files/recorded",
            freeBytes = 1_649_267_441_664L, // 1.5 TB
            totalBytes = 2_199_023_255_552L  // 2.0 TB
        ),
        RecordingVolumeUi(
            id = "usb-sdb1",
            title = "USB Memory (removed)",
            detail = "Unavailable",
            available = false,
            removable = true,
            selected = false,
            recordedPath = "/storage/8765-4321/Android/data/dev.khronos31.epgstation.server/files/recorded",
            freeBytes = null,
            totalBytes = null
        )
    )

    val SingleVolumeLan = EpgStationUiState(
        listenUrls = listOf("http://192.168.1.135:8888/"),
        qrImage = sampleQr,
        baseUrl = "http://127.0.0.1:40772/",
        baseUrlSaveRevision = 1L,
        baseUrlValidationError = null,
        volumes = singleVolume,
        updateBusy = false,
        updatePrompt = null,
        updateSuccessNotice = null
    )

    val StandardLan = EpgStationUiState(
        listenUrls = listOf(
            "http://192.168.1.135:8888/",
            "http://127.0.0.1:8888/"
        ),
        qrImage = sampleQr,
        baseUrl = "http://127.0.0.1:40772/",
        baseUrlSaveRevision = 1L,
        baseUrlValidationError = null,
        volumes = sampleVolumes,
        updateBusy = false,
        updatePrompt = null,
        updateSuccessNotice = null
    )

    val LoopbackOnly = EpgStationUiState(
        listenUrls = listOf("http://127.0.0.1:8888/"),
        qrImage = sampleQr,
        baseUrl = "http://127.0.0.1:40772/",
        baseUrlSaveRevision = 1L,
        baseUrlValidationError = EpgStationBaseUrlPolicy.ERROR,
        volumes = singleVolume,
        updateBusy = false,
        updatePrompt = null,
        updateSuccessNotice = null
    )

    val UpdateAvailable = SingleVolumeLan.let { base ->
        EpgStationUiState(
            listenUrls = base.listenUrls,
            qrImage = base.qrImage,
            baseUrl = base.baseUrl,
            baseUrlSaveRevision = base.baseUrlSaveRevision,
            baseUrlValidationError = null,
            volumes = base.volumes,
            updateBusy = false,
            updatePrompt = UpdatePromptUi.Available("v0.3.2"),
            updateSuccessNotice = null
        )
    }

    val UnknownSourcesPermission = SingleVolumeLan.let { base ->
        EpgStationUiState(
            listenUrls = base.listenUrls,
            qrImage = base.qrImage,
            baseUrl = base.baseUrl,
            baseUrlSaveRevision = base.baseUrlSaveRevision,
            baseUrlValidationError = null,
            volumes = base.volumes,
            updateBusy = false,
            updatePrompt = UpdatePromptUi.UnknownSourcesPermission,
            updateSuccessNotice = null
        )
    }

    val sampleAbout = EpgStationAboutUi(
        appVersion = "0.4.0",
        epgStationVersion = "2.10.0",
        repositories = listOf(
            RepositoryLinkUi("アプリリポジトリ", "https://github.com/Khronos31/dtv-android"),
            RepositoryLinkUi("EPGStationリポジトリ", "https://github.com/l3tnun/EPGStation")
        ),
        licenses = listOf(
            LicenseDocumentUi(
                id = "epgstation",
                title = "EPGStation",
                body = "MIT License\n\nCopyright (c) 2017 l3tnun\n\nPermission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files..."
            ),
            LicenseDocumentUi(
                id = "app",
                title = "dtv-android",
                body = "Apache License\nVersion 2.0, January 2004\nhttp://www.apache.org/licenses/\n\nTERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION..."
            ),
            LicenseDocumentUi(
                id = "ndk-notice",
                title = "Android NDK",
                body = (1..100).joinToString("\n") { "Notice line $it: Copyright Android Open Source Project" }
            )
        ),
        missingLicenseDocuments = emptyList(),
        licenseCoverageNote = "※ 本一覧には同梱された主要バイナリおよびライブラリのライセンス文書を掲載しています。"
    )

    val AboutVisible = SingleVolumeLan.copy(
        about = sampleAbout,
        aboutVisible = true
    )

    val LicenseViewer = SingleVolumeLan.copy(
        about = sampleAbout,
        aboutVisible = true,
        selectedLicenseId = "epgstation"
    )
}

@Preview(name = "SingleVolumeLan", widthDp = 960, heightDp = 540)
@Composable
private fun SingleVolumeLanPreview() {
    EpgStationTvScreen(EpgStationUiFixtures.SingleVolumeLan) { }
}

@Preview(name = "StandardLan", widthDp = 960, heightDp = 540)
@Composable
private fun StandardLanPreview() {
    EpgStationTvScreen(EpgStationUiFixtures.StandardLan) { }
}

@Preview(name = "LoopbackOnly", widthDp = 960, heightDp = 540)
@Composable
private fun LoopbackOnlyPreview() {
    EpgStationTvScreen(EpgStationUiFixtures.LoopbackOnly) { }
}

@Preview(name = "UpdateAvailable", widthDp = 960, heightDp = 540)
@Composable
private fun UpdateAvailablePreview() {
    EpgStationTvScreen(EpgStationUiFixtures.UpdateAvailable) { }
}

@Preview(name = "UnknownSourcesPermission", widthDp = 960, heightDp = 540)
@Composable
private fun UnknownSourcesPermissionPreview() {
    EpgStationTvScreen(EpgStationUiFixtures.UnknownSourcesPermission) { }
}

@Preview(name = "AboutVisible", widthDp = 960, heightDp = 540)
@Composable
private fun AboutVisiblePreview() {
    EpgStationTvScreen(EpgStationUiFixtures.AboutVisible) { }
}

@Preview(name = "LicenseViewer", widthDp = 960, heightDp = 540)
@Composable
private fun LicenseViewerPreview() {
    EpgStationTvScreen(EpgStationUiFixtures.LicenseViewer) { }
}
