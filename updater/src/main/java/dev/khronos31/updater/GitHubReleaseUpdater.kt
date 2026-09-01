package dev.khronos31.updater

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Manual, app-specific GitHub Releases update flow. It never checks by itself. */
class GitHubReleaseUpdater(
    private val context: Context,
    private val component: String,
    private val expectedPackageName: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun check(callback: (CheckResult) -> Unit) {
        executor.execute {
            val result = runCatching { findUpdate() }
                .getOrElse { CheckResult.Failure(it.message ?: it.javaClass.simpleName) }
            mainHandler.post { callback(result) }
        }
    }

    fun downloadAndInstall(update: AvailableUpdate, activity: Activity, callback: (DownloadResult) -> Unit) {
        executor.execute {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                mainHandler.post { callback(DownloadResult.NeedUnknownSourcesPermission) }
                return@execute
            }
            val file = runCatching { download(update) }
                .getOrElse {
                    mainHandler.post { callback(DownloadResult.Failure(it.message ?: it.javaClass.simpleName)) }
                    return@execute
                }
            mainHandler.post {
                try {
                    launchInstaller(file, activity)
                    callback(DownloadResult.InstallerLaunched)
                } catch (error: Exception) {
                    callback(DownloadResult.Failure(error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    fun openUnknownSourcesSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    private fun findUpdate(): CheckResult {
        val installed = installedPackageInfo()
        val installedCode = installed.longVersionCodeCompat()
        val releases = requestJson(RELEASES_URL)
        var newest: AvailableUpdate? = null
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.optString("tag_name")
            val versionText = tag.removePrefix("$component-v")
            val version = SemanticVersion.parse(versionText) ?: continue
            if (!tag.startsWith("$component-v")) continue
            val expectedAsset = "$component-$versionText.apk"
            val assets = release.optJSONArray("assets") ?: continue
            var downloadUrl: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.optString("name") == expectedAsset) {
                    downloadUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    break
                }
            }
            if (downloadUrl == null) continue
            val candidate = AvailableUpdate(versionText, version, expectedAsset, downloadUrl)
            if (candidate.version > (newest?.version ?: SemanticVersion.MIN)) newest = candidate
        }
        return if (newest == null || newest.versionCode <= installedCode) {
            CheckResult.UpToDate(installed.versionName ?: "unknown")
        } else {
            CheckResult.UpdateAvailable(newest)
        }
    }

    private fun download(update: AvailableUpdate): File {
        val targetDir = File(context.cacheDir, "updates/$component").apply { mkdirs() }
        val temporary = File(targetDir, "${update.assetName}.part")
        val target = File(targetDir, update.assetName)
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "dtv-android/$component")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("GitHub returned HTTP ${connection.responseCode}")
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_APK_BYTES) throw IOException("APK is unexpectedly large")
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("APK is unexpectedly large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            verifyDownloadedApk(temporary, update)
            if (target.exists() && !target.delete()) throw IOException("Unable to replace cached APK")
            if (!temporary.renameTo(target)) throw IOException("Unable to finalize downloaded APK")
            return target
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun verifyDownloadedApk(file: File, update: AvailableUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Downloaded file is not a valid APK")
        if (archive.packageName != expectedPackageName) {
            throw IOException("Downloaded APK belongs to ${archive.packageName}, not $expectedPackageName")
        }
        if (archive.versionName != update.versionText || archive.longVersionCodeCompat() != update.versionCode) {
            throw IOException("Downloaded APK metadata does not match the release tag")
        }
        val installed = installedPackageInfo()
        if (certificateDigests(archive) != certificateDigests(installed)) {
            throw IOException("Downloaded APK signature does not match the installed app")
        }
    }

    private fun installedPackageInfo(): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageInfo(expectedPackageName, flags)
    }

    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo.apkContentsSigners.toList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.toList()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString(":") { byte -> "%02X".format(byte) }
        }.toSet()
    }

    private fun launchInstaller(file: File, activity: Activity) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.updater.fileprovider",
            file,
        )
        activity.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun requestJson(url: String): JSONArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "dtv-android/$component")
        }
        return try {
            if (connection.responseCode !in 200..299) throw IOException("GitHub returned HTTP ${connection.responseCode}")
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    sealed interface CheckResult {
        data class UpToDate(val installedVersion: String) : CheckResult
        data class UpdateAvailable(val update: AvailableUpdate) : CheckResult
        data class Failure(val message: String) : CheckResult
    }

    sealed interface DownloadResult {
        data object InstallerLaunched : DownloadResult
        data object NeedUnknownSourcesPermission : DownloadResult
        data class Failure(val message: String) : DownloadResult
    }

    data class AvailableUpdate(
        val versionText: String,
        val version: SemanticVersion,
        val assetName: String,
        val downloadUrl: String,
    ) {
        val versionCode: Long get() = version.versionCode.toLong()
    }

    data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {
        val versionCode: Int get() = major * 10000 + minor * 100 + patch

        override fun compareTo(other: SemanticVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

        override fun toString(): String = "$major.$minor.$patch"

        companion object {
            val MIN = SemanticVersion(0, 0, 0)
            private val PATTERN = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")

            fun parse(text: String): SemanticVersion? {
                val match = PATTERN.matchEntire(text) ?: return null
                val (major, minor, patch) = match.destructured
                return SemanticVersion(major.toInt(), minor.toInt(), patch.toInt())
            }
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/Khronos31/dtv-android/releases?per_page=100"
        private const val MAX_APK_BYTES = 100L * 1024L * 1024L
        private val executor = Executors.newCachedThreadPool()
    }
}
