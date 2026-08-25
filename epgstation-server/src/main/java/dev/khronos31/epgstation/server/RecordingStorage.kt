package dev.khronos31.epgstation.server

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

internal data class RecordingVolume(
    val id: String,
    val title: String,
    val detail: String,
    val removable: Boolean,
    val available: Boolean,
    val recordedDir: File,
    val thumbnailDir: File
)

internal object RecordingStorage {
    const val INTERNAL_ID = "internal"

    fun list(context: Context): List<RecordingVolume> {
        val volumes = mutableListOf<RecordingVolume>()
        val internalRoot = File(context.filesDir, "epgstation")
        volumes += volume(
            id = INTERNAL_ID,
            title = "Internal storage",
            removable = false,
            root = internalRoot,
            available = true
        )
        val manager = context.getSystemService(StorageManager::class.java) ?: return volumes
        val appDirs = context.getExternalFilesDirs(null)?.filterNotNull().orEmpty()
        for (volume in manager.storageVolumes) {
            if (!volume.isRemovable) continue
            val uuid = volume.uuid ?: continue
            val description = volume.getDescription(context) ?: "USB"
            val appDir = appDirs.firstOrNull { dir ->
                dir.path.contains("/$uuid/") ||
                    (Build.VERSION.SDK_INT >= 30 && volume.directory?.let { dir.path.startsWith(it.path) } == true)
            } ?: if (Build.VERSION.SDK_INT >= 30) {
                volume.directory?.let { File(it, "Android/data/${context.packageName}/files") }
            } else {
                null
            }
            val available = appDir != null && (appDir.exists() || appDir.mkdirs()) && appDir.canWrite()
            val root = appDir ?: File("/storage/$uuid/Android/data/${context.packageName}/files")
            volumes += volume(
                id = uuid,
                title = "$description (removable)",
                removable = true,
                root = root,
                available = available
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

    private fun volume(
        id: String,
        title: String,
        removable: Boolean,
        root: File,
        available: Boolean
    ): RecordingVolume {
        val recorded = File(root, "recorded")
        val thumbnail = File(root, "thumbnail")
        val statFile = when {
            available && recorded.exists() -> recorded
            available && root.exists() -> root
            else -> null
        }
        val detail = if (!available) {
            "Not mounted"
        } else {
            val (free, total) = space(statFile ?: root)
            "${formatBytes(free)} free of ${formatBytes(total)}"
        }
        return RecordingVolume(
            id = id,
            title = title,
            detail = detail,
            removable = removable,
            available = available,
            recordedDir = recorded,
            thumbnailDir = thumbnail
        )
    }

    private fun space(file: File): Pair<Long, Long> {
        return try {
            val stat = StatFs(file.absolutePath)
            stat.availableBytes to stat.totalBytes
        } catch (_: Exception) {
            try {
                file.usableSpace to file.totalSpace
            } catch (_: Exception) {
                0L to 0L
            }
        }
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
