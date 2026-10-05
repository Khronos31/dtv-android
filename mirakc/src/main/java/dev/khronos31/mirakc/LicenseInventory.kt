package dev.khronos31.mirakc

import android.content.Context
import android.content.res.AssetManager
import java.security.MessageDigest

internal fun renderLicenseInventory(
    context: Context,
    assets: AssetManager,
    metadata: ApkSourceMetadata?
): String {
    if (metadata == null) return context.getString(R.string.licenses_metadata_unavailable)
    return buildString {
        metadata.components.sortedBy { it.name }.forEachIndexed { componentIndex, component ->
            if (componentIndex > 0) append("\n\n${context.getString(R.string.licenses_separator)}\n\n")
            append(context.getString(R.string.licenses_component_heading, component.name, component.version))
            append('\n')
            append(context.getString(R.string.licenses_component_spdx, component.spdx))
            append('\n')
            append(context.getString(R.string.licenses_component_commit, component.commit))
            append('\n')
            append(context.getString(R.string.licenses_component_source, component.url))
            append("\n\n")

            component.licenseFiles.forEachIndexed { fileIndex, license ->
                if (fileIndex > 0) append("\n\n")
                append(context.getString(R.string.licenses_file_source_path, license.sourcePath))
                append('\n')
                val body = runCatching { assets.open(license.assetPath).use { it.readBytes() } }
                    .getOrNull()
                    ?.takeIf { it.size == license.size && sha256(it) == license.sha256 }
                    ?.toString(Charsets.UTF_8)
                if (body == null) {
                    append(context.getString(R.string.licenses_file_unavailable, license.sourcePath))
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
