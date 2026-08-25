package dev.khronos31.epgstation.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class EpgStationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var supervisor: Thread? = null
    private var child: Process? = null
    private val stopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        stopping.set(false)
        acquireWakeLock()
        createNotificationChannel()
        publish("Starting bundled EPGStation…")
        supervisor = thread(name = "epgstation-supervisor", start = true) { supervise() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A saved Mirakurun URL is applied by the next supervised process.
        child?.destroy()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping.set(true)
        child?.destroy()
        child = null
        supervisor?.interrupt()
        supervisor = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun supervise() {
        var backoffMs = 1_000L
        while (!stopping.get()) {
            try {
                val root = prepareFiles()
                val nativeDir = File(applicationInfo.nativeLibraryDir)
                val node = File(nativeDir, "libepgstation-node.so")
                if (!node.isFile) {
                    throw IOException("Android Node launcher is missing from $nativeDir")
                }
                val sqlite = File(nativeDir, "libnode_sqlite3.so")
                val crc32 = File(nativeDir, "libcrc32_android.so")
                installNativeLoaders(root, sqlite, crc32)
                val process = ProcessBuilder(node.absolutePath, "dist/index.js")
                    .directory(root)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["HOME"] = root.absolutePath
                        environment()["NODE_PATH"] = File(root, "node_modules").absolutePath
                        environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
                        environment()["PATH"] = "${nativeDir.absolutePath}:${environment()["PATH"] ?: ""}"
                        environment()["EPGSTATION_SQLITE3"] = sqlite.absolutePath
                        environment()["EPGSTATION_CRC32"] = crc32.absolutePath
                    }
                    .start()
                child = process
                backoffMs = 1_000L
                val media = RecordingStorage.selected(this)
                publish("Node on :$PORT · ${media.title}")
                val outputThread = thread(name = "epgstation-output", start = true) {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                android.util.Log.i(TAG, line)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                val portThread = thread(name = "epgstation-port-check", start = true) {
                    try {
                        while (!stopping.get() && process.isAlive) {
                            try {
                                Socket().use { socket ->
                                    socket.connect(InetSocketAddress("127.0.0.1", PORT), 250)
                                }
                                publish("Listening on 0.0.0.0:${PORT}")
                                break
                            } catch (_: IOException) {
                                try {
                                    Thread.sleep(500L)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                val exitCode = process.waitFor()
                outputThread.join(1_000L)
                portThread.interrupt()
                portThread.join(1_000L)
                child = null
                if (!stopping.get()) {
                    publish("EPGStation stopped (exit $exitCode); retrying")
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
            } catch (interrupted: InterruptedException) {
                if (!stopping.get()) publish("Supervisor interrupted: ${interrupted.message ?: "unknown error"}")
            } catch (error: Throwable) {
                child = null
                android.util.Log.e(TAG, "EPGStation failed", error)
                publish("EPGStation down: ${error.message ?: error.javaClass.simpleName}")
                try {
                    Thread.sleep(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                } catch (_: InterruptedException) {
                    // onDestroy interrupts the supervisor
                }
            }
        }
    }

    private fun prepareFiles(): File {
        val root = File(filesDir, PAYLOAD_DIR)
        val version = assets.open("payload.version").bufferedReader().use { it.readText().trim() }
        val installedVersion = File(root, "payload.version")
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
        if (installedVersion != version || !File(root, "dist/index.js").isFile) {
            deleteTree(root)
            root.mkdirs()
            copyAssetTree("", root)
        }
        File(root, "data").mkdirs()
        File(root, "drop").mkdirs()
        File(root, "config").mkdirs()
        File(root, "logs/Operator").mkdirs()
        File(root, "logs/Service").mkdirs()
        File(root, "logs/EPGUpdater").mkdirs()
        deleteTree(File(root, "runtime"))
        deleteTree(File(root, "native"))
        writeRuntimeConfig(root)
        return root
    }

    private fun writeRuntimeConfig(root: File) {
        val template = File(root, "config/config.yml.template")
        check(template.isFile) { "upstream config.yml.template is missing" }
        val url = getSharedPreferences(MainActivity.PREFERENCES, MODE_PRIVATE)
            .getString(MainActivity.KEY_MIRAKURUN_URL, MainActivity.DEFAULT_MIRAKURUN_URL)
            ?: MainActivity.DEFAULT_MIRAKURUN_URL
        val quotedUrl = "'${url.replace("'", "''")}'"
        val media = RecordingStorage.prepare(RecordingStorage.selected(this))
        val recordedPath = media.recordedDir.absolutePath.replace("'", "''")
        val thumbnailPath = media.thumbnailDir.absolutePath.replace("'", "''")
        var sawPort = false
        var sawClientSocketPort = false
        val lines = template.readLines().map { line ->
            when {
                line.startsWith("port:") -> {
                    sawPort = true
                    "port: $PORT"
                }
                line.startsWith("clientSocketioPort:") -> {
                    sawClientSocketPort = true
                    "clientSocketioPort: $PORT"
                }
                line.startsWith("mirakurunPath:") -> "mirakurunPath: $quotedUrl"
                line.trimStart().startsWith("path:") && line.startsWith("      path:") ->
                    "      path: '$recordedPath'"
                line.startsWith("thumbnail:") ->
                    "thumbnail: '$thumbnailPath'"
                else -> line
            }
        }.toMutableList()
        if (!sawPort) lines.add(0, "port: $PORT")
        if (!sawClientSocketPort) {
            val portIndex = lines.indexOfFirst { it.startsWith("port:") }
            lines.add(portIndex + 1, "clientSocketioPort: $PORT")
        }
        File(root, "config/config.yml").writeText(preferRawLiveTs(lines.joinToString("\n") + "\n"))
        for (name in listOf("operatorLogConfig", "serviceLogConfig", "epgUpdaterLogConfig")) {
            val dest = File(root, "config/$name.yml")
            if (dest.isFile) continue
            val sample = File(root, "config/$name.sample.yml")
            check(sample.isFile) { "upstream $name.sample.yml is missing" }
            sample.copyTo(dest, overwrite = false)
        }
    }

    private fun preferRawLiveTs(yaml: String): String {
        val marker = "            m2ts:"
        val liveTs = yaml.indexOf("        ts:\n$marker")
        if (liveTs < 0) return yaml
        val m2ts = yaml.indexOf(marker, liveTs)
        val next = yaml.indexOf("\n            m2tsll:", m2ts)
        if (m2ts < 0 || next < 0) return yaml
        return yaml.substring(0, m2ts + marker.length) +
            "\n                - name: 無変換\n" +
            yaml.substring(next + 1)
    }

    private fun installNativeLoaders(root: File, sqlite: File, crc32: File) {
        if (!sqlite.isFile) throw IOException("sqlite3 addon missing: ${sqlite.path}")
        if (!crc32.isFile) throw IOException("crc32 addon missing: ${crc32.path}")
        // Node resolves symlinks before picking a loader. A .so realpath is
        // parsed as JavaScript, so point the JS bindings at process.dlopen.
        File(root, "node_modules/sqlite3/lib/sqlite3-binding.js")
            .writeText("process.dlopen(module, process.env.EPGSTATION_SQLITE3);\n")
        val loader = "process.dlopen(module, process.env.EPGSTATION_CRC32);\n"
        for (pkg in listOf("crc32-android-arm-eabi", "crc32-android-arm64")) {
            val dir = File(root, "node_modules/@node-rs/$pkg")
            if (!dir.isDirectory) continue
            File(dir, "dlopen.js").writeText(loader)
            val pkgJson = File(dir, "package.json")
            if (pkgJson.isFile) {
                pkgJson.writeText(
                    pkgJson.readText().replace(Regex("\"main\"\\s*:\\s*\"[^\"]+\""), "\"main\": \"dlopen.js\"")
                )
            }
        }
        for (stale in listOf(
            "node_modules/sqlite3/build/Release/node_sqlite3.node",
            "node_modules/@node-rs/crc32/crc32.android-arm-eabi.node",
            "node_modules/@node-rs/crc32/crc32.android-arm64.node",
            "node_modules/@node-rs/crc32-android-arm-eabi/crc32.android-arm-eabi.node",
            "node_modules/@node-rs/crc32-android-arm64/crc32.android-arm64.node"
        )) {
            val file = File(root, stale)
            try {
                Os.remove(file.absolutePath)
            } catch (_: ErrnoException) {
                if (file.exists()) file.delete()
            }
        }
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val entries = assets.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (entry in entries) {
            val childAsset = if (assetPath.isEmpty()) entry else "$assetPath/$entry"
            copyAssetTree(childAsset, File(target, entry))
        }
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }

    private fun acquireWakeLock() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "epgstation-server:resident").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "EPGStation Server", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun publish(text: String) {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("EPGStation Server")
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        private const val TAG = "EPGStationServer"
        private const val PAYLOAD_DIR = "epgstation"
        private const val CHANNEL = "epgstation-server-service"
        private const val ID = 40773
        internal const val PORT = 8888
    }
}
