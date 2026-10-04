package dev.khronos31.mirakc

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.security.MessageDigest

class LicenseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_licenses)

        val content = findViewById<TextView>(R.id.licenses_content_text)
        content.text = renderLicenseInventory(ApkSourceMetadata.readOrNull(assets))
        content.requestFocus()
    }

    private fun renderLicenseInventory(metadata: ApkSourceMetadata?): String {
        if (metadata == null) return getString(R.string.licenses_metadata_unavailable)

        return buildString {
            metadata.components.sortedBy { it.name }.forEachIndexed { componentIndex, component ->
                if (componentIndex > 0) append("\n\n${getString(R.string.licenses_separator)}\n\n")
                append(getString(R.string.licenses_component_heading, component.name, component.version))
                append('\n')
                append(getString(R.string.licenses_component_spdx, component.spdx))
                append('\n')
                append(getString(R.string.licenses_component_commit, component.commit))
                append('\n')
                append(getString(R.string.licenses_component_source, component.url))
                append("\n\n")

                component.licenseFiles.forEachIndexed { fileIndex, license ->
                    if (fileIndex > 0) append("\n\n")
                    append(getString(R.string.licenses_file_source_path, license.sourcePath))
                    append('\n')
                    val bytes = runCatching {
                        assets.open(license.assetPath).use { it.readBytes() }
                    }.getOrNull()
                    val body = bytes
                        ?.takeIf { it.size == license.size && sha256(it) == license.sha256 }
                        ?.toString(Charsets.UTF_8)
                    if (body == null) {
                        append(getString(R.string.licenses_file_unavailable, license.sourcePath))
                    } else {
                        append(body.trimEnd())
                    }
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finish()
    }
}
