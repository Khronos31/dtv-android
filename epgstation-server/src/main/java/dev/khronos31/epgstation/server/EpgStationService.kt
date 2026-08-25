package dev.khronos31.epgstation.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
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
                val abi = androidAbi()
                installAbiAddon(root, abi)
                val runtime = File(root, "runtime/$abi")
                val node = File(runtime, "node")
                if (!node.isFile || !node.setExecutable(true)) {
                    throw IOException("Android Node launcher is missing or not executable: ${node.path}")
                }
                val process = ProcessBuilder(node.absolutePath, "dist/index.js")
                    .directory(root)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["HOME"] = root.absolutePath
                        environment()["NODE_PATH"] = File(root, "node_modules").absolutePath
                        environment()["LD_LIBRARY_PATH"] = runtime.absolutePath
                        environment()["PATH"] = "${runtime.absolutePath}:${environment()["PATH"] ?: ""}"
                    }
                    .start()
                child = process
                backoffMs = 1_000L
                publish("Node process running; waiting for :${PORT}")
                val outputThread = thread(name = "epgstation-output", start = true) {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            android.util.Log.i(TAG, line)
                        }
                    }
                }
                val portThread = thread(name = "epgstation-port-check", start = true) {
                    while (!stopping.get() && process.isAlive) {
                        try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress("127.0.0.1", PORT), 250)
                            }
                            publish("Listening on 0.0.0.0:${PORT}")
                            break
                        } catch (_: IOException) {
                            Thread.sleep(500L)
                        } catch (_: InterruptedException) {
                            break
                        }
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
        File(root, "recorded").mkdirs()
        File(root, "thumbnail").mkdirs()
        File(root, "drop").mkdirs()
        File(root, "config").mkdirs()
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
                    "      path: '${File(root, "recorded").absolutePath.replace("'", "''")}'"
                line.startsWith("thumbnail:") ->
                    "thumbnail: '${File(root, "thumbnail").absolutePath.replace("'", "''")}'"
                else -> line
            }
        }.toMutableList()
        if (!sawPort) lines.add(0, "port: $PORT")
        if (!sawClientSocketPort) {
            val portIndex = lines.indexOfFirst { it.startsWith("port:") }
            lines.add(portIndex + 1, "clientSocketioPort: $PORT")
        }
        File(root, "config/config.yml").writeText(lines.joinToString("\n") + "\n")
    }

    private fun installAbiAddon(root: File, abi: String) {
        val source = File(root, "native/$abi/node_sqlite3.node")
        check(source.isFile) { "sqlite3 Android addon missing for $abi" }
        val destination = File(root, "node_modules/sqlite3/build/Release/node_sqlite3.node")
        destination.parentFile?.mkdirs()
        source.inputStream().use { input -> FileOutputStream(destination).use { input.copyTo(it) } }
    }

    private fun androidAbi(): String {
        return Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: error("Unsupported CPU; this APK requires armeabi-v7a or arm64-v8a")
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
        private const val PORT = 8888
    }
}
