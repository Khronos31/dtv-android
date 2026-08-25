package dev.khronos31.mirakc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.content.pm.ServiceInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal data class ChannelDefinition(val name: String, val type: String, val channel: String)

private data class StreamClient(val socket: Socket, val output: BufferedOutputStream)

class MirakcService : Service() {
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val streamLock = Any()
    private var httpServer: MirakcHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var usbConnection: UsbDeviceConnection? = null
    private var usbParcel: ParcelFileDescriptor? = null
    private var streamSession: StreamSession? = null
    private var lastError = "none"
    private var initialized = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != USB_PERMISSION_ACTION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                lastError = "none"
                statusText = "USB permission granted: ${device.deviceName}"
            } else {
                lastError = "USB permission was denied"
            }
            publishStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerUsbReceiver()
        acquireWakeLock()
        createNotificationChannel()
        startResidentForeground()
        httpServer = MirakcHttpServer(this).also { it.start() }
        initialized = true
        requestUsbPermissionIfNeeded()
        publishStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) onCreate()
        when (intent?.action) {
            ACTION_REQUEST_USB -> requestUsbPermissionIfNeeded()
        }
        publishStatus()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        httpServer?.stop()
        synchronized(streamLock) {
            streamSession?.stop()
            streamSession = null
            closeUsb()
        }
        if (receiverRegistered) unregisterReceiver(usbReceiver)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        statusText = "Stopped"
        super.onDestroy()
    }

    private fun startResidentForeground() {
        val notificationBuilder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = notificationBuilder
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("mirakc")
            .setContentText("Mirakurun API listening on 0.0.0.0:40772")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mirakc:usb-tuner").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "mirakc", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun registerUsbReceiver() {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, IntentFilter(USB_PERMISSION_ACTION), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, IntentFilter(USB_PERMISSION_ACTION))
        }
        receiverRegistered = true
    }

    private fun requestUsbPermissionIfNeeded() {
        val device = supportedDevices().firstOrNull()
        if (device == null) {
            lastError = "No supported Siano USB device found (3275:0080, 187f:0600, 187f:0302)"
            publishStatus()
            return
        }
        if (usbManager.hasPermission(device)) {
            lastError = "none"
            publishStatus()
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(USB_PERMISSION_ACTION).setPackage(packageName), flags
        )
        usbManager.requestPermission(device, permissionIntent)
        statusText = "USB permission dialog requested for ${device.deviceName}"
        publishStatus()
    }

    private fun supportedDevices(): List<UsbDevice> = usbManager.deviceList.values.filter {
        (it.vendorId == 0x3275 && it.productId == 0x0080) ||
            (it.vendorId == 0x187f && (it.productId == 0x0600 || it.productId == 0x0302))
    }

    private fun openUsbForStream(): Int {
        synchronized(streamLock) {
            usbParcel?.let { return it.fd }
            val device = supportedDevices().firstOrNull()
                ?: throw IOException("No supported Siano USB device is attached")
            if (!usbManager.hasPermission(device)) {
                throw IOException("USB permission has not been granted")
            }
            val connection = usbManager.openDevice(device)
                ?: throw IOException("UsbManager.openDevice failed for ${device.deviceName}")
            val parcel = try {
                ParcelFileDescriptor.fromFd(connection.fileDescriptor)
            } catch (error: Exception) {
                connection.close()
                throw IOException("Unable to duplicate USB fd", error)
            }
            usbConnection = connection
            usbParcel = parcel
            return parcel.fd
        }
    }

    private fun closeUsb() {
        usbParcel?.close()
        usbParcel = null
        usbConnection?.close()
        usbConnection = null
    }

    private fun firmwareFile(): File {
        val file = File(filesDir, "isdbt_rio.inp")
        if (!file.isFile || file.length() == 0L) {
            assets.open("isdbt_rio.inp").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    private fun sianoExecutable(): File {
        val destination = File(filesDir, "siano-ts")
        val source = File(applicationInfo.nativeLibraryDir, "libsiano-ts.so")
        if (!source.isFile) throw IOException("siano-ts executable was not packaged for this ABI")
        if (!destination.isFile || destination.length() != source.length()) {
            source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            if (!destination.setExecutable(true, false)) {
                throw IOException("Unable to mark siano-ts executable")
            }
        }
        return destination
    }

    internal fun attachStream(channel: ChannelDefinition, socket: Socket, output: OutputStream): Boolean {
        synchronized(streamLock) {
            val existing = streamSession
            if (existing != null && existing.channel.channel != channel.channel) return false
            val session = existing ?: try {
                val created = StreamSession(channel)
                created.start()
                streamSession = created
                created
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
                closeUsb()
                publishStatus()
                return false
            }
            session.addClient(socket, output)
            publishStatus()
            return true
        }
    }

    private fun streamEnded(session: StreamSession, error: String?) {
        synchronized(streamLock) {
            if (streamSession !== session) return
            if (error != null) lastError = error
            streamSession = null
            session.closeClients()
            closeUsb()
            publishStatus()
        }
    }

    private inner class StreamSession(val channel: ChannelDefinition) {
        private val clients = CopyOnWriteArrayList<StreamClient>()
        private var process: NativeUsbProcess.StartedProcess? = null
        private var reader: InputStream? = null

        fun start() {
            val executable = sianoExecutable()
            val firmware = firmwareFile()
            val usbFd = openUsbForStream()
            val started = NativeUsbProcess.start(executable.absolutePath, firmware.absolutePath, channel.channel.removePrefix("T").toInt(), usbFd)
            process = started
            reader = ParcelFileDescriptor.AutoCloseInputStream(started.output)
            Thread({ readLoop(started) }, "mirakc-ts-${channel.channel}").apply {
                isDaemon = true
                start()
            }
        }

        fun addClient(socket: Socket, output: OutputStream) {
            clients += StreamClient(socket, BufferedOutputStream(output))
        }

        private fun readLoop(started: NativeUsbProcess.StartedProcess) {
            var failure: String? = null
            try {
                val input = BufferedInputStream(reader!!)
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    for (client in clients.toList()) {
                        try {
                            client.output.write(buffer, 0, count)
                            client.output.flush()
                        } catch (_: IOException) {
                            clients.remove(client)
                            closeClient(client)
                        }
                    }
                }
            } catch (error: Exception) {
                failure = error.message ?: error.javaClass.simpleName
            } finally {
                try { reader?.close() } catch (_: Exception) { }
                NativeUsbProcess.stop(started.pid)
                streamEnded(this, failure)
            }
        }

        fun stop() {
            process?.let { NativeUsbProcess.stop(it.pid) }
            try { reader?.close() } catch (_: Exception) { }
            closeClients()
        }

        fun closeClients() {
            clients.toList().forEach {
                clients.remove(it)
                closeClient(it)
            }
        }

        private fun closeClient(client: StreamClient) {
            try { client.output.close() } catch (_: Exception) { }
            try { client.socket.close() } catch (_: Exception) { }
        }
    }

    private fun publishStatus() {
        val device = supportedDevices().firstOrNull()
        val granted = device != null && usbManager.hasPermission(device)
        val stream = synchronized(streamLock) { streamSession?.channel?.channel ?: "none" }
        statusText = buildString {
            append("USB: ")
            append(if (granted) "granted" else "not granted")
            append(if (device != null) " (${device.deviceName})" else "")
            append("\nListener: 0.0.0.0:40772")
            append("\nStream: ").append(stream)
            append("\nLast error: ").append(lastError)
        }
    }

    internal fun channelsJson(): String = CHANNELS.joinToString(prefix = "[", postfix = "]") {
        "{\"name\":\"${json(it.name)}\",\"type\":\"${it.type}\",\"channel\":\"${it.channel}\"}"
    }

    internal fun tunersJson(): String {
        val devices = supportedDevices()
        val count = maxOf(devices.size, 1)
        return (0 until count).joinToString(prefix = "[", postfix = "]") { index ->
            val device = devices.getOrNull(index)
            val granted = device != null && usbManager.hasPermission(device)
            val free = synchronized(streamLock) { streamSession == null }
            val name = device?.deviceName ?: "Siano USB tuner #$index"
            "{\"name\":\"${json(name)}\",\"types\":[\"GR\"],\"isAvailable\":$granted,\"isFree\":${free && granted}}"
        }
    }

    internal fun channelFor(type: String, value: String): ChannelDefinition? {
        if (type != "GR") return null
        val normalized = if (value.startsWith("T")) value else "T$value"
        return CHANNELS.firstOrNull { it.channel == normalized }
    }

    companion object {
        const val ACTION_REQUEST_USB = "dev.khronos31.mirakc.REQUEST_USB"
        private const val USB_PERMISSION_ACTION = "dev.khronos31.mirakc.USB_PERMISSION"
        private const val NOTIFICATION_CHANNEL = "mirakc-service"
        private const val NOTIFICATION_ID = 40772
        private val CHANNELS = listOf(
            ChannelDefinition("TOKYO MX", "GR", "T16"),
            ChannelDefinition("フジテレビジョン", "GR", "T21"),
            ChannelDefinition("TBS", "GR", "T22"),
            ChannelDefinition("テレビ東京", "GR", "T23"),
            ChannelDefinition("テレビ朝日", "GR", "T24"),
            ChannelDefinition("日本テレビ", "GR", "T25"),
            ChannelDefinition("NHK Eテレ東京", "GR", "T26"),
            ChannelDefinition("NHK総合・東京", "GR", "T27"),
            ChannelDefinition("チバテレビ", "GR", "T30"),
            ChannelDefinition("tvk", "GR", "T31"),
            ChannelDefinition("テレ玉", "GR", "T32")
        )

        @Volatile
        var statusText: String = "Starting mirakc service..."

        internal fun json(value: String): String = buildString {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(char)
                }
            }
        }
    }
}

private class MirakcHttpServer(private val service: MirakcService) {
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        running = true
        executor.execute {
            try {
                serverSocket = ServerSocket(40772, 50, InetAddress.getByName("0.0.0.0"))
                while (running) executor.execute { acceptClient(serverSocket!!.accept()) }
            } catch (_: IOException) {
                if (running) MirakcService.statusText = "HTTP listener failed on 0.0.0.0:40772"
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) { }
        executor.shutdownNow()
    }

    private fun acceptClient(socket: Socket) {
        var handedOff = false
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val request = input.readLine()?.split(' ') ?: return
            if (request.size < 2 || request[0] != "GET") {
                respond(socket, 405, "Method Not Allowed", "{\"error\":\"GET required\"}")
                return
            }
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = request[1].substringBefore('?')
            when {
                path == "/api/version" -> respond(socket, 200, "OK", "{\"current\":\"3.4.82\",\"latest\":\"3.4.82\"}")
                path == "/api/status" -> respond(socket, 200, "OK", "{}")
                path == "/api/channels" -> respond(socket, 200, "OK", service.channelsJson())
                path == "/api/tuners" -> respond(socket, 200, "OK", service.tunersJson())
                path.startsWith("/api/channels/") && path.endsWith("/stream") -> {
                    val parts = path.split('/')
                    val channel = if (parts.size == 6) service.channelFor(parts[3], parts[4]) else null
                    if (channel == null) {
                        respond(socket, 404, "Not Found", "{\"error\":\"channel not found\"}")
                    } else {
                        val output = socket.getOutputStream()
                        writeHeaders(output, 200, "OK", "video/MP2T", null)
                        handedOff = service.attachStream(channel, socket, output)
                        if (!handedOff) {
                            try { socket.close() } catch (_: Exception) { }
                        }
                    }
                }
                else -> respond(socket, 404, "Not Found", "{\"error\":\"route not implemented\"}")
            }
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) { }
        } finally {
            if (!handedOff) try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun respond(socket: Socket, code: Int, reason: String, body: String) {
        val output = socket.getOutputStream()
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        writeHeaders(output, code, reason, "application/json; charset=utf-8", bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun writeHeaders(output: OutputStream, code: Int, reason: String, contentType: String, length: Int?) {
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Connection: close\r\n")
            if (length != null) append("Content-Length: $length\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }
}
