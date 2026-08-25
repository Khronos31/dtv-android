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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal data class ChannelDefinition(val name: String, val type: String, val channel: String)

private data class StreamClient(
    val socket: Socket,
    val output: BufferedOutputStream,
    val filter: TsServiceFilter?
)

class MirakcService : Service() {
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val streamLock = Any()
    private var httpServer: MirakcHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var usbConnection: UsbDeviceConnection? = null
    private var usbParcel: ParcelFileDescriptor? = null
    private var readerConnection: UsbDeviceConnection? = null
    private var readerParcel: ParcelFileDescriptor? = null
    private var streamSession: StreamSession? = null
    private var lastError = "none"
    private var initialized = false
    private val epg = EpgStore()
    private var scanThread: Thread? = null
    @Volatile private var scanning = false
    @Volatile private var scanLabel = "idle"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != USB_PERMISSION_ACTION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                lastError = "none"
                statusText = "USB permission granted: ${device.deviceName}"
                requestUsbPermissionIfNeeded()
            } else {
                lastError = "USB permission was denied"
                publishStatus()
            }
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
        try {
            epg.load(epgCacheFile())
        } catch (_: Exception) {
        }
        requestUsbPermissionIfNeeded()
        if (supportedDevices().any { usbManager.hasPermission(it) }) startEpgScan()
        publishStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) onCreate()
        when (intent?.action) {
            ACTION_REQUEST_USB -> requestUsbPermissionIfNeeded()
            ACTION_SCAN_EPG -> startEpgScan()
        }
        publishStatus()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scanning = false
        scanThread?.interrupt()
        httpServer?.stop()
        synchronized(streamLock) {
            streamSession?.stop()
            streamSession = null
            closeUsb()
            closeReaderUsb()
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
            // USB Device permission is only granted after the dialog. Android 14
            // connectedDevice FGS requires that permission already, so the
            // resident HTTP listener starts as dataSync.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
        val pending = (supportedDevices() + readerDevices()).firstOrNull { !usbManager.hasPermission(it) }
        if (pending != null) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, pending.deviceId, Intent(USB_PERMISSION_ACTION).setPackage(packageName), flags
            )
            usbManager.requestPermission(pending, permissionIntent)
            statusText = "USB permission dialog requested for ${pending.deviceName}"
            publishStatus()
            return
        }
        if (supportedDevices().isEmpty()) {
            lastError = "No supported Siano USB device found (3275:0080, 187f:0600, 187f:0302)"
            publishStatus()
            return
        }
        lastError = "none"
        startEpgScan()
        publishStatus()
    }

    private fun supportedDevices(): List<UsbDevice> = usbManager.deviceList.values.filter {
        (it.vendorId == 0x3275 && it.productId == 0x0080) ||
            (it.vendorId == 0x187f && (it.productId == 0x0600 || it.productId == 0x0302))
    }

    private fun isSmartCardReader(device: UsbDevice): Boolean {
        if (device.vendorId == 0x3275 || device.vendorId == 0x187f) return false
        if (device.vendorId == 0x04E6) return true
        if (device.deviceClass == 0x0B) return true
        for (index in 0 until device.interfaceCount) {
            if (device.getInterface(index).interfaceClass == 0x0B) return true
        }
        return false
    }

    private fun readerDevices(): List<UsbDevice> = usbManager.deviceList.values.filter(::isSmartCardReader)

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

    private fun openReaderUsb(): Int {
        readerParcel?.let { return it.fd }
        val device = readerDevices().firstOrNull { usbManager.hasPermission(it) }
            ?: throw IOException("No B-CAS reader permission")
        val connection = usbManager.openDevice(device)
            ?: throw IOException("UsbManager.openDevice failed for ${device.deviceName}")
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass == 0x0B) {
                connection.claimInterface(usbInterface, true)
            }
        }
        val parcel = try {
            ParcelFileDescriptor.fromFd(connection.fileDescriptor)
        } catch (error: Exception) {
            connection.close()
            throw IOException("Unable to duplicate reader fd", error)
        }
        readerConnection = connection
        readerParcel = parcel
        return parcel.fd
    }

    private fun closeReaderUsb() {
        readerParcel?.close()
        readerParcel = null
        readerConnection?.close()
        readerConnection = null
    }

    private fun firmwareFile(): File {
        val file = File(filesDir, "isdbt_rio.inp")
        if (!file.isFile || file.length() == 0L) {
            assets.open("isdbt_rio.inp").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    private fun sianoExecutable(): File {
        val source = File(applicationInfo.nativeLibraryDir, "libsiano-ts.so")
        if (!source.isFile) throw IOException("siano-ts executable was not packaged for this ABI")
        return source
    }

    private fun epgCacheFile(): File = File(filesDir, "epg-cache.json")

    internal fun startEpgScan() {
        if (scanThread?.isAlive == true) return
        if (!supportedDevices().any { usbManager.hasPermission(it) }) return
        scanning = true
        scanThread = Thread({
            try {
                runEpgScan()
            } finally {
                scanning = false
                scanLabel = "idle"
                publishStatus()
            }
        }, "mirakc-epg-scan").also { it.start() }
    }

    private fun runEpgScan() {
        val channels = CHANNELS
        for ((index, channel) in channels.withIndex()) {
            if (!scanning) break
            scanLabel = "${channel.channel} (${index + 1}/${channels.size})"
            publishStatus()
            val started = synchronized(streamLock) {
                val existing = streamSession
                if (existing != null && !existing.scanOnly) return@synchronized false
                if (existing != null) {
                    existing.stop()
                    streamSession = null
                    closeUsb()
                }
                try {
                    val session = StreamSession(channel, scanOnly = true)
                    session.start()
                    streamSession = session
                    true
                } catch (error: Exception) {
                    lastError = error.message ?: error.javaClass.simpleName
                    closeUsb()
                    false
                }
            }
            if (!started) {
                Thread.sleep(400)
                continue
            }
            val deadline = System.currentTimeMillis() + SCAN_MS
            while (scanning && System.currentTimeMillis() < deadline) {
                if (synchronized(streamLock) { streamSession?.scanOnly != true }) break
                Thread.sleep(250)
            }
            synchronized(streamLock) {
                val session = streamSession
                if (session != null && session.scanOnly) {
                    session.stop()
                    streamSession = null
                    closeUsb()
                }
            }
            try {
                epg.pruneExpired()
                epg.save(epgCacheFile())
            } catch (_: Exception) {
            }
        }
    }

    internal fun attachStream(
        channel: ChannelDefinition,
        socket: Socket,
        output: OutputStream,
        serviceId: Int? = null,
        onReady: () -> Unit
    ): Boolean {
        synchronized(streamLock) {
            val existing = streamSession
            if (existing != null && !existing.alive) {
                existing.stop()
                streamSession = null
                closeUsb()
            }
            val current = streamSession
            if (current != null && current.channel.channel != channel.channel) {
                if (!current.scanOnly) return false
                current.stop()
                streamSession = null
                closeUsb()
            }
            val session = streamSession ?: try {
                val created = StreamSession(channel, scanOnly = false)
                created.start()
                streamSession = created
                created
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
                closeUsb()
                publishStatus()
                return false
            }
            session.scanOnly = false
            onReady()
            session.addClient(socket, output, serviceId)
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

    private inner class StreamSession(val channel: ChannelDefinition, @Volatile var scanOnly: Boolean) {
        private val clients = CopyOnWriteArrayList<StreamClient>()
        private var process: NativeUsbProcess.StartedProcess? = null
        private var reader: InputStream? = null
        private val parser = TsSiParser(channel, epg)
        @Volatile var alive = true

        fun start() {
            val executable = sianoExecutable()
            val firmware = firmwareFile()
            val usbFd = openUsbForStream()
            val readerFd = try {
                openReaderUsb()
            } catch (_: Exception) {
                -1
            }
            val started = NativeUsbProcess.start(
                executable.absolutePath,
                firmware.absolutePath,
                channel.channel.removePrefix("T").toInt(),
                usbFd,
                readerFd
            )
            process = started
            reader = ParcelFileDescriptor.AutoCloseInputStream(started.output)
            Thread({ readLoop(started) }, "mirakc-ts-${channel.channel}").apply {
                isDaemon = true
                start()
            }
        }

        fun addClient(socket: Socket, output: OutputStream, serviceId: Int?) {
            val filter = serviceId?.let { TsServiceFilter(it) }
            clients += StreamClient(socket, BufferedOutputStream(output), filter)
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
                    try {
                        parser.feed(buffer, 0, count)
                    } catch (_: Exception) {
                    }
                    for (client in clients.toList()) {
                        try {
                            val payload = client.filter?.push(buffer, 0, count)
                            if (payload != null) {
                                if (payload.isNotEmpty()) {
                                    client.output.write(payload)
                                    client.output.flush()
                                }
                            } else {
                                client.output.write(buffer, 0, count)
                                client.output.flush()
                            }
                        } catch (_: IOException) {
                            clients.remove(client)
                            closeClient(client)
                        }
                    }
                    if (!scanOnly && clients.isEmpty()) break
                }
            } catch (error: Exception) {
                failure = error.message ?: error.javaClass.simpleName
            } finally {
                alive = false
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
        val reader = readerDevices().firstOrNull()
        val readerGranted = reader != null && usbManager.hasPermission(reader)
        statusText = buildString {
            append("USB: ")
            append(if (granted) "granted" else "not granted")
            append(if (device != null) " (${device.deviceName})" else "")
            append("\nB-CAS: ")
            when {
                reader == null -> append("no reader")
                !readerGranted -> append("reader permission needed (${reader.deviceName})")
                else -> append("reader ${reader.productName ?: reader.deviceName}")
            }
            append("\nListener: 0.0.0.0:40772")
            append("\nStream: ").append(stream)
            val counts = epg.counts()
            append("\nEPG: ").append(counts.first).append(" services, ").append(counts.second).append(" programs")
            append("\nScan: ").append(if (scanning) scanLabel else "idle")
            append("\nLast error: ").append(lastError)
        }
    }

    internal fun channelsJson(): String = CHANNELS.joinToString(prefix = "[", postfix = "]") { ch ->
        val services = epg.services().filter { it.channel == ch.channel.removePrefix("T") || it.channel == ch.channel }
        val body = services.joinToString(prefix = "[", postfix = "]") { serviceJson(it) }
        "{\"name\":\"${json(ch.name)}\",\"type\":\"${ch.type}\",\"channel\":\"${ch.channel.removePrefix("T")}\",\"services\":$body}"
    }

    internal fun servicesJson(): String =
        epg.services().joinToString(prefix = "[", postfix = "]") { serviceJson(it) }

    internal fun programsJson(): String =
        epg.programs().joinToString(prefix = "[", postfix = "]") { programJson(it) }

    internal fun serviceJsonById(id: Long): String? = epg.service(id)?.let { serviceJson(it) }

    internal fun programJsonById(id: Long): String? = epg.program(id)?.let { programJson(it) }

    internal fun programsJsonForService(id: Long): String =
        epg.programsForService(id).joinToString(prefix = "[", postfix = "]") { programJson(it) }

    internal fun serviceForId(id: Long): EpgService? = epg.service(id)

    internal fun channelForProgram(id: Long): ChannelDefinition? {
        val program = epg.program(id) ?: return null
        val epgService = epg.service(program.networkId * 100000L + program.serviceId) ?: return null
        return channelFor("GR", epgService.channel)
    }

    internal fun serviceIdForProgram(id: Long): Int? = epg.program(id)?.serviceId

    internal fun subscribeEpg(listener: (String, String) -> Unit) = epg.addListener(listener)

    internal fun unsubscribeEpg(listener: (String, String) -> Unit) = epg.removeListener(listener)

    private fun serviceJson(service: EpgService): String = buildString {
        append("{")
        append("\"id\":${service.id},")
        append("\"serviceId\":${service.serviceId},")
        append("\"networkId\":${service.networkId},")
        append("\"name\":\"${json(service.name)}\",")
        append("\"type\":${service.type},")
        append("\"logoId\":-1,")
        append("\"hasLogoData\":false,")
        if (service.remoteControlKeyId != null) append("\"remoteControlKeyId\":${service.remoteControlKeyId},")
        append("\"epgReady\":true,")
        append("\"epgUpdatedAt\":${service.epgUpdatedAt},")
        append("\"channel\":{\"type\":\"${service.channelType}\",\"channel\":\"${json(service.channel)}\"}")
        append("}")
    }

    private fun programJson(program: EpgProgram): String = buildString {
        append("{")
        append("\"id\":${program.id},")
        append("\"eventId\":${program.eventId},")
        append("\"serviceId\":${program.serviceId},")
        append("\"networkId\":${program.networkId},")
        append("\"startAt\":${program.startAt},")
        append("\"duration\":${program.duration},")
        append("\"isFree\":${program.isFree}")
        if (program.name != null) append(",\"name\":\"${json(program.name)}\"")
        if (program.description != null) append(",\"description\":\"${json(program.description)}\"")
        if (program.genres.isNotEmpty()) {
            append(",\"genres\":")
            append(program.genres.joinToString(prefix = "[", postfix = "]") {
                "{\"lv1\":${it[0]},\"lv2\":${it[1]},\"un1\":${it[2]},\"un2\":${it[3]}}"
            })
        }
        append("}")
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
        const val ACTION_SCAN_EPG = "dev.khronos31.mirakc.SCAN_EPG"
        private const val USB_PERMISSION_ACTION = "dev.khronos31.mirakc.USB_PERMISSION"
        private const val NOTIFICATION_CHANNEL = "mirakc-service"
        private const val NOTIFICATION_ID = 40772
        private const val SCAN_MS = 16_000L
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
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
}

private class MirakcHttpServer(private val service: MirakcService) {
    private val executor = Executors.newFixedThreadPool(12)
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        running = true
        acceptThread = Thread({
            try {
                serverSocket = ServerSocket(40772, 16, InetAddress.getByName("0.0.0.0"))
                while (running) {
                    val socket = try {
                        serverSocket!!.accept()
                    } catch (_: IOException) {
                        if (running) continue else break
                    }
                    executor.execute { acceptClient(socket) }
                }
            } catch (_: IOException) {
                if (running) MirakcService.statusText = "HTTP listener failed on 0.0.0.0:40772"
            }
        }, "mirakc-http-accept").also { it.start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) { }
        acceptThread?.interrupt()
        acceptThread = null
        executor.shutdownNow()
    }

    private fun acceptClient(socket: Socket) {
        var handedOff = false
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val request = input.readLine()?.split(' ') ?: return
            if (request.size < 2 || (request[0] != "GET" && request[0] != "HEAD")) {
                respond(socket, 405, "Method Not Allowed", "{\"error\":\"GET required\"}")
                return
            }
            val headOnly = request[0] == "HEAD"
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = request[1].substringBefore('?')
            when {
                path == "/api/docs" || path == "/docs" -> respond(socket, 200, "OK", OPENAPI_DOCS)
                path == "/api/version" -> respond(socket, 200, "OK", "{\"current\":\"3.4.82\",\"latest\":\"3.4.82\"}")
                path == "/api/status" -> respond(socket, 200, "OK", "{}")
                path == "/api/channels" -> respond(socket, 200, "OK", service.channelsJson())
                path == "/api/tuners" -> respond(socket, 200, "OK", service.tunersJson())
                path == "/api/services" -> respond(socket, 200, "OK", service.servicesJson())
                path == "/api/programs" -> respond(socket, 200, "OK", service.programsJson())
                path == "/events" -> {
                    if (headOnly) {
                        writeHeaders(socket.getOutputStream(), 200, "OK", "text/event-stream", null)
                    } else {
                        handedOff = true
                        serveEvents(socket)
                    }
                }
                path.startsWith("/api/services/") && path.endsWith("/programs") -> {
                    val id = path.removePrefix("/api/services/").removeSuffix("/programs").toLongOrNull()
                    if (id == null) respond(socket, 404, "Not Found", "{\"error\":\"service not found\"}")
                    else respond(socket, 200, "OK", service.programsJsonForService(id))
                }
                path.startsWith("/api/services/") && path.endsWith("/stream") -> {
                    val id = path.removePrefix("/api/services/").removeSuffix("/stream").toLongOrNull()
                    val epgService = id?.let { service.serviceForId(it) }
                    val channel = epgService?.let { service.channelFor("GR", it.channel) }
                    if (channel == null) {
                        respond(socket, 404, "Not Found", "{\"error\":\"service not found\"}")
                    } else if (headOnly) {
                        writeHeaders(socket.getOutputStream(), 200, "OK", "video/MP2T", null)
                    } else {
                        socket.soTimeout = 0
                        val output = socket.getOutputStream()
                        handedOff = service.attachStream(channel, socket, output, epgService.serviceId) {
                            writeHeaders(output, 200, "OK", "video/MP2T", null)
                        }
                        if (!handedOff) {
                            respond(socket, 503, "Service Unavailable", "{\"error\":\"tuner busy\"}")
                        }
                    }
                }
                path.startsWith("/api/services/") -> {
                    val id = path.removePrefix("/api/services/").toLongOrNull()
                    val body = id?.let { service.serviceJsonById(it) }
                    if (body == null) respond(socket, 404, "Not Found", "{\"error\":\"service not found\"}")
                    else respond(socket, 200, "OK", body)
                }
                path.startsWith("/api/programs/") && path.endsWith("/stream") -> {
                    val id = path.removePrefix("/api/programs/").removeSuffix("/stream").toLongOrNull()
                    val stored = id?.let { service.channelForProgram(it) }
                    val sid = id?.let { service.serviceIdForProgram(it) }
                    if (stored == null) {
                        respond(socket, 404, "Not Found", "{\"error\":\"program not found\"}")
                    } else if (headOnly) {
                        writeHeaders(socket.getOutputStream(), 200, "OK", "video/MP2T", null)
                    } else {
                        socket.soTimeout = 0
                        val output = socket.getOutputStream()
                        handedOff = service.attachStream(stored, socket, output, sid) {
                            writeHeaders(output, 200, "OK", "video/MP2T", null)
                        }
                        if (!handedOff) {
                            respond(socket, 503, "Service Unavailable", "{\"error\":\"tuner busy\"}")
                        }
                    }
                }
                path.startsWith("/api/programs/") -> {
                    val id = path.removePrefix("/api/programs/").toLongOrNull()
                    val body = id?.let { service.programJsonById(it) }
                    if (body == null) respond(socket, 404, "Not Found", "{\"error\":\"program not found\"}")
                    else respond(socket, 200, "OK", body)
                }
                path.startsWith("/api/channels/") && path.endsWith("/stream") -> {
                    val parts = path.split('/')
                    val channel = when {
                        parts.size == 6 -> service.channelFor(parts[3], parts[4])
                        parts.size == 8 && parts[5] == "services" -> service.channelFor(parts[3], parts[4])
                        else -> null
                    }
                    if (channel == null) {
                        respond(socket, 404, "Not Found", "{\"error\":\"channel not found\"}")
                    } else if (headOnly) {
                        writeHeaders(socket.getOutputStream(), 200, "OK", "video/MP2T", null)
                    } else {
                        socket.soTimeout = 0
                        val output = socket.getOutputStream()
                        handedOff = service.attachStream(channel, socket, output, null) {
                            writeHeaders(output, 200, "OK", "video/MP2T", null)
                        }
                        if (!handedOff) {
                            respond(socket, 503, "Service Unavailable", "{\"error\":\"tuner busy\"}")
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

    private fun serveEvents(socket: Socket) {
        socket.soTimeout = 0
        val output = socket.getOutputStream()
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val listener: (String, String) -> Unit = { event, data ->
            synchronized(output) {
                output.write("event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
        }
        service.subscribeEpg(listener)
        try {
            output.write(": connected\n\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val input = socket.getInputStream()
            val buf = ByteArray(8)
            while (running) {
                val n = try {
                    input.read(buf)
                } catch (_: IOException) {
                    break
                }
                if (n < 0) break
            }
        } finally {
            service.unsubscribeEpg(listener)
            try { socket.close() } catch (_: Exception) { }
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

    companion object {
        // The mirakurun Node client fetches this before getStatus(). Path-level
        // parameters and tags must exist; missing either throws in client.call().
        private const val OPENAPI_DOCS =
            """{"swagger":"2.0","info":{"title":"mirakc","version":"3.4.82"},"basePath":"/api","paths":{"/status":{"parameters":[],"get":{"operationId":"getStatus","tags":["status"],"parameters":[]}},"/version":{"parameters":[],"get":{"operationId":"checkVersion","tags":["version"],"parameters":[]}},"/channels":{"parameters":[],"get":{"operationId":"getChannels","tags":["channels"],"parameters":[]}},"/tuners":{"parameters":[],"get":{"operationId":"getTuners","tags":["tuners"],"parameters":[]}},"/services":{"parameters":[],"get":{"operationId":"getServices","tags":["services"],"parameters":[]}},"/services/{id}":{"parameters":[{"name":"id","in":"path","required":true,"type":"integer"}],"get":{"operationId":"getService","tags":["services"],"parameters":[]}},"/programs":{"parameters":[],"get":{"operationId":"getPrograms","tags":["programs"],"parameters":[]}},"/programs/{id}":{"parameters":[{"name":"id","in":"path","required":true,"type":"integer"}],"get":{"operationId":"getProgram","tags":["programs"],"parameters":[]}},"/channels/{type}/{channel}/stream":{"parameters":[{"name":"type","in":"path","required":true,"type":"string"},{"name":"channel","in":"path","required":true,"type":"string"},{"name":"decode","in":"query","required":false,"type":"integer"}],"get":{"operationId":"getChannelStream","tags":["stream"],"parameters":[]}},"/services/{id}/stream":{"parameters":[{"name":"id","in":"path","required":true,"type":"integer"},{"name":"decode","in":"query","required":false,"type":"integer"}],"get":{"operationId":"getServiceStream","tags":["stream"],"parameters":[]}},"/programs/{id}/stream":{"parameters":[{"name":"id","in":"path","required":true,"type":"integer"},{"name":"decode","in":"query","required":false,"type":"integer"}],"get":{"operationId":"getProgramStream","tags":["stream"],"parameters":[]}}}}"""
    }
}
