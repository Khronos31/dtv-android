package dev.khronos31.epgstation.server

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

internal enum class RemovableAccess {
    Writable,
    NeedsAllFilesAccess,
    NeedsProcessRestart,
    Unavailable,
}

internal data class RecordingVolume(
    val id: String,
    val title: String,
    val detail: String,
    val removable: Boolean,
    val available: Boolean,
    val access: RemovableAccess,
    val recordedDir: File,
    val thumbnailDir: File,
    val freeBytes: Long?,
    val totalBytes: Long?
)

internal object RecordingStorage {
    const val INTERNAL_ID = "internal"

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun list(context: Context): List<RecordingVolume> {
        val volumes = mutableListOf<RecordingVolume>()
        // This root is persistent: the EPGStation payload lives in its own child directory.
        val internalRoot = EpgStationLayout.persistentRoot(context.filesDir)
        volumes += volume(
            id = INTERNAL_ID,
            title = "Internal storage",
            removable = false,
            root = internalRoot,
            available = true,
            access = RemovableAccess.Writable
        )
        val manager = context.getSystemService(StorageManager::class.java) ?: return volumes
        val appDirs = context.getExternalFilesDirs(null)?.filterNotNull().orEmpty()
        val allFilesAccess = hasAllFilesAccess()
        for (storageVolume in manager.storageVolumes) {
            if (!storageVolume.isRemovable) continue
            val uuid = storageVolume.uuid ?: continue
            if (!isSafeVolumeUuid(uuid)) continue
            val description = storageVolume.getDescription(context) ?: "USB"
            val volumeDirectory = if (Build.VERSION.SDK_INT >= 30) storageVolume.directory else null
            val candidates = removableRootCandidates(
                uuid = uuid,
                packageName = context.packageName,
                visibleAppDirs = appDirs,
                volumeDirectory = volumeDirectory,
                allFilesAccess = allFilesAccess
            )
            val usable = firstUsableRoot(candidates, ::canUse)
            val access = classifyRemovableAccess(
                mounted = storageVolume.state == Environment.MEDIA_MOUNTED,
                writable = usable != null,
                allFilesAccess = allFilesAccess,
                canRequestAllFilesAccess = Build.VERSION.SDK_INT >= 30
            )
            val root = usable
                ?: candidates.lastOrNull()
                ?: hiddenVolumeRoot(uuid, context.packageName, Build.VERSION.SDK_INT)
            volumes += volume(
                id = uuid,
                title = "$description (removable)",
                removable = true,
                root = root,
                available = access == RemovableAccess.Writable,
                access = access
            )
        }
        return volumes
    }

    fun selected(context: Context): RecordingVolume {
        val volumes = list(context)
        val saved = context.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_RECORDED_VOLUME, null)
        if (saved != null) {
            volumes.firstOrNull { it.id == saved && it.available }?.let { return it }
        }
        return volumes.firstOrNull { it.removable && it.available }
            ?: volumes.first { it.id == INTERNAL_ID }
    }

    fun save(context: Context, id: String) {
        context.getSharedPreferences(MainActivity.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(MainActivity.KEY_RECORDED_VOLUME, id)
            .apply()
    }

    fun prepare(volume: RecordingVolume): RecordingVolume {
        volume.recordedDir.mkdirs()
        volume.thumbnailDir.mkdirs()
        return volume
    }

    private fun canUse(dir: File): Boolean = (dir.isDirectory || dir.mkdirs()) && dir.canWrite()

    private fun volume(
        id: String,
        title: String,
        removable: Boolean,
        root: File,
        available: Boolean,
        access: RemovableAccess
    ): RecordingVolume {
        val recorded = File(root, "recorded")
        val thumbnail = File(root, "thumbnail")
        val statFile = when {
            available && recorded.exists() -> recorded
            available && root.exists() -> root
            else -> null
        }
        val capacity = if (available) space(statFile ?: root) else null
        val detail = when (access) {
            RemovableAccess.NeedsAllFilesAccess -> "All files access required"
            RemovableAccess.NeedsProcessRestart -> "Restart required to use this volume"
            RemovableAccess.Writable, RemovableAccess.Unavailable ->
                capacity?.let { (free, total) -> "${formatBytes(free)} free of ${formatBytes(total)}" }
                    ?: "Not mounted"
        }
        return RecordingVolume(
            id = id,
            title = title,
            detail = detail,
            removable = removable,
            available = available,
            access = access,
            recordedDir = recorded,
            thumbnailDir = thumbnail,
            freeBytes = capacity?.first,
            totalBytes = capacity?.second
        )
    }

    private fun space(file: File): Pair<Long, Long> {
        var existing = file
        while (!existing.exists()) {
            existing = existing.parentFile ?: break
        }
        return try {
            val stat = StatFs(existing.absolutePath)
            stat.availableBytes to stat.totalBytes
        } catch (_: Exception) {
            try {
                existing.usableSpace to existing.totalSpace
            } catch (_: Exception) {
                0L to 0L
            }
        }
    }

    internal fun hiddenVolumeRoot(uuid: String, packageName: String, sdkInt: Int): File {
        val files = "Android/data/$packageName/files"
        val base = if (sdkInt >= 30) "/mnt/media_rw/$uuid" else "/storage/$uuid"
        return File("$base/$files")
    }

    internal fun isSafeVolumeUuid(uuid: String): Boolean {
        if (uuid.isEmpty() || uuid.length > 64) return false
        return uuid.all { it.isLetterOrDigit() || it == '-' }
    }

    /**
     * Visible app directories stay first. A USB volume with mountFlags=0 has no
     * /storage path; all-files access can write /mnt/media_rw once gid 1077 is applied.
     */
    internal fun removableRootCandidates(
        uuid: String,
        packageName: String,
        visibleAppDirs: List<File>,
        volumeDirectory: File?,
        allFilesAccess: Boolean,
    ): List<File> {
        if (!isSafeVolumeUuid(uuid)) return emptyList()
        val appFiles = "Android/data/$packageName/files"
        val matched = visibleAppDirs.filter { dir ->
            dir.path.contains("/$uuid/") ||
                (volumeDirectory != null && dir.path.startsWith(volumeDirectory.path))
        }
        val fromVolume = volumeDirectory?.let { File(it, appFiles) }
        val fromMediaRw = if (allFilesAccess) File("/mnt/media_rw/$uuid/$appFiles") else null
        return (matched + listOfNotNull(fromVolume, fromMediaRw)).distinctBy { it.path }
    }

    internal fun firstUsableRoot(candidates: List<File>, usable: (File) -> Boolean): File? =
        candidates.firstOrNull(usable)

    internal fun classifyRemovableAccess(
        mounted: Boolean,
        writable: Boolean,
        allFilesAccess: Boolean,
        canRequestAllFilesAccess: Boolean,
    ): RemovableAccess {
        if (writable) return RemovableAccess.Writable
        if (!mounted) return RemovableAccess.Unavailable
        if (allFilesAccess) return RemovableAccess.NeedsProcessRestart
        if (canRequestAllFilesAccess) return RemovableAccess.NeedsAllFilesAccess
        return RemovableAccess.Unavailable
    }

    private fun formatBytes(value: Long): String {
        if (value <= 0L) return "0 B"
        val gb = value / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1) {
            String.format("%.0f GB", gb)
        } else {
            String.format("%.0f MB", value / (1024.0 * 1024.0))
        }
    }
}
