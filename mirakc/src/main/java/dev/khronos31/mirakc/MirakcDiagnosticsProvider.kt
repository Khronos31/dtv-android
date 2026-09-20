package dev.khronos31.mirakc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Shell-only, app-UID-scoped diagnostics for acceptance evidence.  The
 * provider has no caller-controlled PID or path: it discovers only the
 * candidate's own process tree and returns bounded JSON.
 */
class MirakcDiagnosticsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        enforceDumpPermission()
        if (uri.authority != AUTHORITY || uri.path != "/fds") throw IllegalArgumentException("unsupported diagnostic URI")
        return MatrixCursor(arrayOf("json")).apply {
            addRow(arrayOf(snapshotJson()))
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceDumpPermission()
        return when (method) {
            METHOD_SNAPSHOT -> Bundle().apply { putString("json", snapshotJson()) }
            METHOD_TRIGGER_UPDATE_SCHEDULES -> Bundle().apply {
                putBoolean("triggered", MirakcDiagnostics.triggerUpdateSchedules?.invoke() == true)
            }
            else -> throw IllegalArgumentException("unsupported diagnostic method")
        }
    }

    private fun enforceDumpPermission() {
        val currentContext = context ?: throw SecurityException("provider context is unavailable")
        if (Binder.getCallingUid() != Process.SHELL_UID ||
            currentContext.checkCallingPermission(android.Manifest.permission.DUMP) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("shell UID and android.permission.DUMP are required")
        }
    }

    private fun snapshotJson(): String {
        val startedNanos = System.nanoTime()
        val processes = discoverProcessTree()
        val result = JSONObject().apply {
            put("schema", 1)
            put("uid", Process.myUid())
            put("processes", JSONArray())
        }
        val array = result.getJSONArray("processes")
        var bytes = result.toString().length
        for (process in processes.sortedBy { it.pid }) {
            ensureWithinDeadline(startedNanos)
            val entry = JSONObject().apply {
                put("pid", process.pid)
                put("ppid", process.ppid)
                put("argv0", process.argv0)
                put("fds", JSONArray())
            }
            val fds = entry.getJSONArray("fds")
            val fdEntries = process.fdDirectory.listFiles()?.sortedBy { it.name }
                ?: throw IOException("diagnostic fd directory is unreadable")
            for (fd in fdEntries) {
                ensureWithinDeadline(startedNanos)
                if (fds.length() >= MAX_FDS_PER_PROCESS) throw IOException("diagnostic fd count exceeds bound")
                val target: String = try {
                    Os.readlink(fd.absolutePath)
                } catch (error: ErrnoException) {
                    throw IOException("diagnostic fd target is unreadable", error)
                }
                val fdNumber = fd.name.toIntOrNull() ?: throw IOException("diagnostic fd name is invalid")
                if (fdNumber < 0 || target.length > MAX_TARGET_LENGTH) {
                    throw IOException("diagnostic fd record exceeds bound")
                }
                val item = JSONObject().apply {
                    put("fd", fdNumber)
                    put("target", target)
                }
                fds.put(item)
            }
            bytes += entry.toString().length
            if (bytes > MAX_OUTPUT_BYTES) throw IOException("diagnostic output exceeds bound")
            array.put(entry)
        }
        return result.toString()
    }

    private data class ProcessRecord(val pid: Int, val ppid: Int, val argv0: String, val fdDirectory: File)

    private fun discoverProcessTree(): List<ProcessRecord> {
        val startedNanos = System.nanoTime()
        val proc = File("/proc")
        val directories = proc.listFiles() ?: throw IOException("/proc is unreadable")
        val records = directories.mapNotNull { directory ->
            ensureWithinDeadline(startedNanos)
            val pid = directory.name.toIntOrNull() ?: return@mapNotNull null
            val status = File(directory, "status")
            val statusText = try {
                status.readText().take(MAX_STATUS_LENGTH)
            } catch (_: IOException) {
                return@mapNotNull null
            }
            val uid = Regex("^Uid:\\s+(\\d+)", RegexOption.MULTILINE).find(statusText)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (uid != Process.myUid()) return@mapNotNull null
            val ppid = Regex("^PPid:\\s+(\\d+)", RegexOption.MULTILINE).find(statusText)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val commandLine = try {
                File(directory, "cmdline").readBytes().take(MAX_CMDLINE_LENGTH).toByteArray()
                    .toString(Charsets.UTF_8).substringBefore('\u0000')
            } catch (_: IOException) {
                return@mapNotNull null
            }
            if (commandLine.isEmpty()) return@mapNotNull null
            ProcessRecord(pid, ppid, commandLine, File(directory, "fd"))
        }
        if (records.size > MAX_PROCESSES) throw IOException("diagnostic process count exceeds bound")
        ensureWithinDeadline(startedNanos)
        val byPid = records.associateBy { it.pid }
        val roots = records.filter { it.argv0 == context!!.packageName || it.argv0.startsWith(context!!.packageName + ":") }.map { it.pid }.toSet()
        if (roots.isEmpty()) throw IOException("candidate process root is not observable")
        val descendants = records.filter { record ->
            ensureWithinDeadline(startedNanos)
            var pid: Int? = record.pid
            repeat(MAX_PARENT_DEPTH) {
                if (pid == null) return@filter false
                if (pid in roots) return@filter true
                pid = byPid[pid]?.ppid
            }
            false
        }
        if (descendants.isEmpty()) throw IOException("candidate process tree is empty")
        return descendants
    }

    private fun ensureWithinDeadline(startedNanos: Long) {
        if (System.nanoTime() - startedNanos > MAX_SNAPSHOT_NANOS) {
            throw IOException("diagnostic snapshot exceeded deadline")
        }
    }

    override fun getType(uri: Uri): String? = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY = "dev.khronos31.mirakc.diagnostics"
        const val METHOD_SNAPSHOT = "dump_fds"
        const val METHOD_TRIGGER_UPDATE_SCHEDULES = "trigger_update_schedules"
        private const val MAX_FDS_PER_PROCESS = 256
        private const val MAX_PROCESSES = 128
        private const val MAX_OUTPUT_BYTES = 256 * 1024
        private const val MAX_STATUS_LENGTH = 8192
        private const val MAX_CMDLINE_LENGTH = 1024
        private const val MAX_TARGET_LENGTH = 1024
        private const val MAX_PARENT_DEPTH = 32
        private const val MAX_SNAPSHOT_NANOS = 1_000_000_000L
    }
}

internal object MirakcDiagnostics {
    @Volatile var triggerUpdateSchedules: (() -> Boolean)? = null
}
