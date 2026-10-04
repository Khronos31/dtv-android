package dev.khronos31.mirakc

import android.content.res.AssetManager
import org.json.JSONObject

internal data class ApkLicenseFile(
    val sourcePath: String,
    val assetPath: String,
    val sha256: String,
    val size: Int
)

internal data class ApkSourceComponent(
    val name: String,
    val version: String,
    val commit: String,
    val url: String,
    val spdx: String,
    val licenseFiles: List<ApkLicenseFile>
)

internal data class ApkSourceMetadata(val components: List<ApkSourceComponent>) {
    fun component(name: String): ApkSourceComponent? = components.firstOrNull { it.name == name }

    companion object {
        private const val ASSET_MANIFEST = "source-metadata/manifest.json"
        private const val KIND = "mirakc-apk-source-metadata"

        fun readOrNull(assets: AssetManager): ApkSourceMetadata? = try {
            assets.open(ASSET_MANIFEST).bufferedReader(Charsets.UTF_8).use { reader ->
                val manifest = JSONObject(reader.readText())
                if (manifest.optInt("schema") != 1 || manifest.optString("kind") != KIND) {
                    return null
                }
                val jsonComponents = manifest.optJSONArray("components") ?: return null
                val components = mutableListOf<ApkSourceComponent>()
                for (index in 0 until jsonComponents.length()) {
                    val item = jsonComponents.optJSONObject(index) ?: continue
                    val name = item.nonBlank("name") ?: continue
                    val version = item.nonBlank("version") ?: continue
                    val commit = item.nonBlank("commit") ?: continue
                    val url = item.nonBlank("url") ?: continue
                    val spdx = item.nonBlank("spdx") ?: continue
                    val licenseFiles = mutableListOf<ApkLicenseFile>()
                    val entries = item.optJSONArray("license_files")
                    if (entries != null) {
                        for (fileIndex in 0 until entries.length()) {
                            val file = entries.optJSONObject(fileIndex) ?: continue
                            val sourcePath = file.nonBlank("source_path") ?: continue
                            val assetPath = file.nonBlank("apk_path") ?: continue
                            if (!isSafeLicenseAssetPath(assetPath)) continue
                            val sha256 = file.nonBlank("sha256") ?: continue
                            if (!sha256.matches(Regex("[0-9a-f]{64}"))) continue
                            val size = file.optInt("size", -1).takeIf { it >= 0 } ?: continue
                            licenseFiles.add(ApkLicenseFile(sourcePath, assetPath, sha256, size))
                        }
                    }
                    components.add(ApkSourceComponent(name, version, commit, url, spdx, licenseFiles))
                }
                ApkSourceMetadata(components).takeIf { it.component("dtv-android") != null }
            }
        } catch (_: Exception) {
            null
        }

        private fun isSafeLicenseAssetPath(path: String): Boolean =
            path.startsWith("source-metadata/licenses/") &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

private fun JSONObject.nonBlank(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }
