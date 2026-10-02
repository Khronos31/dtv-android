package dev.khronos31.epgstation.server

import java.io.File
import java.io.IOException

internal data class EpgStationDirectories(
    val root: File,
    val payload: File,
    val data: File
)

/**
 * Keeps the installed EPGStation payload separate from files that must survive
 * APK and payload updates. The legacy root remains stable so recordings and
 * SQLite data keep their existing paths.
 */
internal object EpgStationLayout {
    const val ROOT_DIR = "epgstation"
    const val PAYLOAD_DIR = "payload"

    private const val STAGING_DIR = ".payload-staging"
    private const val BACKUP_DIR = ".payload-backup"
    private val PERSISTENT_CONFIG_FILES = listOf(
        "config.yml",
        "operatorLogConfig.yml",
        "serviceLogConfig.yml",
        "epgUpdaterLogConfig.yml"
    )

    fun persistentRoot(filesDir: File): File = File(filesDir, ROOT_DIR)

    fun prepare(
        filesDir: File,
        version: String,
        installPayload: (File) -> Unit,
        createSymbolicLink: (target: File, link: File) -> Unit,
        readSymbolicLink: (link: File) -> String?
    ): EpgStationDirectories {
        val root = persistentRoot(filesDir)
        ensureDirectory(root)

        val data = File(root, "data")
        ensureDirectory(data)
        val persistentConfig = File(root, "config")
        ensureDirectory(persistentConfig)

        val payload = File(root, PAYLOAD_DIR)
        val staging = File(root, STAGING_DIR)
        val backup = File(root, BACKUP_DIR)

        // Recover an interrupted directory swap before considering another update.
        if (!payload.exists() && backup.exists() && !backup.renameTo(payload)) {
            throw IOException("Could not restore the previous EPGStation payload")
        }

        if (isInstalled(payload, version)) {
            deleteTree(staging, readSymbolicLink)
            // A backup can remain if the process died after activating staging.
            runCatching { deleteTree(backup, readSymbolicLink) }
        } else {
            deleteTree(staging, readSymbolicLink)
            ensureDirectory(staging)
            try {
                installPayload(staging)
                validatePayload(staging, version)
                ensureDataLink(File(staging, "data"), data, createSymbolicLink, readSymbolicLink)
                ensureConfigLinks(staging, persistentConfig, createSymbolicLink, readSymbolicLink)
                activate(staging, payload, backup, readSymbolicLink)
                deleteTree(backup, readSymbolicLink)
            } catch (error: Throwable) {
                runCatching { deleteTree(staging, readSymbolicLink) }
                throw error
            }
        }

        ensureDataLink(File(payload, "data"), data, createSymbolicLink, readSymbolicLink)
        ensureConfigLinks(payload, persistentConfig, createSymbolicLink, readSymbolicLink)
        return EpgStationDirectories(root, payload, data)
    }

    internal fun deleteTree(file: File, readSymbolicLink: (link: File) -> String?) {
        if (readSymbolicLink(file) != null) {
            if (!file.delete() && file.exists()) throw IOException("Could not remove $file")
            return
        }
        val canonicalPath = try {
            file.canonicalPath
        } catch (_: IOException) {
            // A symlink loop cannot be traversed; delete only the link itself.
            if (!file.delete() && file.exists()) throw IOException("Could not remove $file")
            return
        }
        if (canonicalPath != file.absolutePath) {
            // In particular, payload/data points outside the payload tree.
            if (!file.delete() && file.exists()) throw IOException("Could not remove $file")
            return
        }
        if (!file.exists()) return
        if (file.isDirectory) {
            val children = file.listFiles() ?: throw IOException("Could not list $file")
            children.forEach { deleteTree(it, readSymbolicLink) }
        }
        if (!file.delete() && file.exists()) throw IOException("Could not remove $file")
    }

    private fun isInstalled(payload: File, version: String): Boolean {
        val installedVersion = runCatching {
            File(payload, "payload.version").takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull()
        return installedVersion == version && File(payload, "dist/index.js").isFile
    }

    private fun validatePayload(payload: File, version: String) {
        val installedVersion = File(payload, "payload.version")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
        if (installedVersion != version || !File(payload, "dist/index.js").isFile) {
            throw IOException("The staged EPGStation payload is incomplete")
        }
    }

    private fun activate(
        staging: File,
        payload: File,
        backup: File,
        readSymbolicLink: (link: File) -> String?
    ) {
        deleteTree(backup, readSymbolicLink)
        val hadPayload = payload.exists()
        if (hadPayload && !payload.renameTo(backup)) {
            throw IOException("Could not preserve the previous EPGStation payload")
        }
        if (staging.renameTo(payload)) return

        if (hadPayload && !backup.renameTo(payload)) {
            throw IOException("Could not install or restore the EPGStation payload")
        }
        throw IOException("Could not activate the staged EPGStation payload")
    }

    private fun ensureDataLink(
        link: File,
        data: File,
        createSymbolicLink: (target: File, link: File) -> Unit,
        readSymbolicLink: (link: File) -> String?
    ) {
        if (pathExists(link, readSymbolicLink)) {
            if (pointsTo(link, data, readSymbolicLink)) return

            // The APK payload does not include data/. Accept and replace only
            // an empty directory; never overwrite content that may be user data.
            if (link.isDirectory && link.listFiles()?.isEmpty() == true) {
                deleteTree(link, readSymbolicLink)
            } else {
                throw IOException("Refusing to replace non-empty payload data path: $link")
            }
        }

        ensurePersistentFileLink(link, data, createSymbolicLink, readSymbolicLink)
    }

    private fun ensureConfigLinks(
        payload: File,
        persistentConfig: File,
        createSymbolicLink: (target: File, link: File) -> Unit,
        readSymbolicLink: (link: File) -> String?
    ) {
        for (name in PERSISTENT_CONFIG_FILES) {
            ensurePersistentFileLink(
                link = File(payload, "config/$name"),
                target = File(persistentConfig, name),
                createSymbolicLink = createSymbolicLink,
                readSymbolicLink = readSymbolicLink
            )
        }
    }

    private fun ensurePersistentFileLink(
        link: File,
        target: File,
        createSymbolicLink: (target: File, link: File) -> Unit,
        readSymbolicLink: (link: File) -> String?
    ) {
        link.parentFile?.let(::ensureDirectory)
        if (pathExists(link, readSymbolicLink)) {
            if (pointsTo(link, target, readSymbolicLink)) return
            throw IOException("Refusing to replace payload path with persistent file link: $link")
        }

        createSymbolicLink(target, link)
        if (!pointsTo(link, target, readSymbolicLink)) {
            throw IOException("Persistent file link points to the wrong target: $link")
        }
    }

    private fun pointsTo(link: File, target: File, readSymbolicLink: (link: File) -> String?): Boolean {
        val rawTarget = readSymbolicLink(link) ?: return false
        val resolvedTarget = File(rawTarget).let { path ->
            if (path.isAbsolute) path else File(link.parentFile, rawTarget)
        }
        return runCatching { resolvedTarget.canonicalFile == target.canonicalFile }.getOrDefault(false)
    }

    private fun pathExists(file: File, readSymbolicLink: (link: File) -> String?): Boolean {
        if (file.exists()) return true
        return readSymbolicLink(file) != null
    }

    private fun ensureDirectory(directory: File) {
        if (directory.isDirectory) return
        if (directory.exists() || (!directory.mkdirs() && !directory.isDirectory)) {
            throw IOException("Could not create directory $directory")
        }
    }
}
