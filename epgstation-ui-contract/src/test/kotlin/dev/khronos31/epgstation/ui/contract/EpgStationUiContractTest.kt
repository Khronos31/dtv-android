package dev.khronos31.epgstation.ui.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgStationUiContractTest {
    @Test
    fun baseUrlPolicyTrimsAndAddsTrailingSlashWithoutChangingExistingSlash() {
        assertEquals(
            BaseUrlValidation.Valid("https://example.test:40772/api/"),
            EpgStationBaseUrlPolicy.validate("  https://example.test:40772/api  ")
        )
        assertEquals(
            BaseUrlValidation.Valid("http://127.0.0.1:40772/"),
            EpgStationBaseUrlPolicy.validate("http://127.0.0.1:40772/")
        )
    }

    @Test
    fun baseUrlPolicyRejectsRelativeAndUnsupportedUrls() {
        assertEquals(BaseUrlValidation.Invalid, EpgStationBaseUrlPolicy.validate("localhost:40772"))
        assertEquals(BaseUrlValidation.Invalid, EpgStationBaseUrlPolicy.validate("ftp://example.test/"))
        assertEquals(BaseUrlValidation.Invalid, EpgStationBaseUrlPolicy.validate("https:///missing-host"))
    }

    @Test
    fun stateAndQrImageCopyCallerCollectionsAndPixels() {
        val urls = mutableListOf("http://192.0.2.2:40772/")
        val volumes = mutableListOf(RecordingVolumeUi("internal", "Internal", "1 GB free", true, false, true, "/data", 1_000L, 2_000L))
        val sourcePixels = intArrayOf(1, 2, 3, 4)
        val qr = QrImageUi(2, 2, sourcePixels)
        val state = EpgStationUiState(urls, qr, "http://127.0.0.1/", 4, null, volumes, false, null, null)

        urls.clear()
        volumes.clear()
        sourcePixels[0] = 9

        assertEquals(listOf("http://192.0.2.2:40772/"), state.listenUrls)
        assertEquals(1, state.volumes.size)
        assertEquals(1, state.qrImage?.copyPixels()?.first())
        val returned = state.qrImage?.copyPixels()
        returned?.set(1, 99)
        assertFalse(state.qrImage?.copyPixels()?.contains(99) == true)
        assertTrue(state.volumes.single().selected)
        assertEquals(1_000L, state.volumes.single().freeBytes)
        assertEquals(2_000L, state.volumes.single().totalBytes)
        assertEquals(4L, state.baseUrlSaveRevision)
    }

    @Test
    fun saveRevisionCanChangeEvenWhenNormalizedUrlIsUnchanged() {
        val first = EpgStationUiState(emptyList(), null, "http://example.test/", 0, null, emptyList(), false, null, null)
        val savedAgain = EpgStationUiState(emptyList(), null, "http://example.test/", 1, null, emptyList(), false, null, null)

        assertEquals(first.baseUrl, savedAgain.baseUrl)
        assertEquals(0L, first.baseUrlSaveRevision)
        assertEquals(1L, savedAgain.baseUrlSaveRevision)
    }

    @Test
    fun aboutSnapshotCopiesRepositoriesLicensesAndMissingFileList() {
        val repositories = mutableListOf(RepositoryLinkUi("App", "https://example.test/app"))
        val licenses = mutableListOf(LicenseDocumentUi("app", "Apache", "license body"))
        val missing = mutableListOf("NOTICE")
        val about = EpgStationAboutUi("0.3.1", "2.10.0", repositories, licenses, missing, "coverage note")

        repositories.clear()
        licenses.clear()
        missing.clear()

        assertEquals("0.3.1", about.appVersion)
        assertEquals("2.10.0", about.epgStationVersion)
        assertEquals("https://example.test/app", about.repositories.single().url)
        assertEquals("license body", about.licenses.single().body)
        assertEquals(listOf("NOTICE"), about.missingLicenseDocuments)
    }
}
