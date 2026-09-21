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
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class MirakcService : Service() {
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val terrestrialSettings by lazy {
        TerrestrialChannelSettingsStore(
            AndroidStringSettings(getSharedPreferences(TERRESTRIAL_SETTINGS, MODE_PRIVATE))
        )
    }
    private val configurationApplyPending = AtomicBoolean(false)
    @Volatile private var mirakcSupervisor: MirakcSupervisor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var lastError = "none"
    private var initialized = false
    private val px4FirmwareAcquirer by lazy {
        Px4FirmwareAcquirer(
            destinationDirectory = { getExternalFilesDir(null) },
            fwtool = { File(applicationInfo.nativeLibraryDir, "libmirakc-px4-fwtool.so") },
            openFwtoolAsset = { name -> assets.open("px4-fwtool/$name") }
        )
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != USB_PERMISSION_ACTION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                lastError = "none"
                statusText = "USB permission granted: ${device.deviceName}"
                requestUsbPermissionIfNeeded()
                mirakcSupervisor?.reconfigure()
            } else {
                lastError = "USB permission was denied"
                publishStatus()
            }
        }
    }

    private val usbLifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
                intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                requestUsbPermissionIfNeeded()
            }
            mirakcSupervisor?.reconfigure()
            publishStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            terrestrialSettings.recoverIncompleteApply()
        } catch (error: Exception) {
            lastError = "Terrestrial settings recovery failed: ${error.message ?: error.javaClass.simpleName}"
        }
        registerUsbReceiver()
        acquireWakeLock()
        createNotificationChannel()
        startResidentForeground()
        mirakcSupervisor = MirakcSupervisor(
            context = this,
            tunerDevices = {
                supportedDevices().filter { usbManager.hasPermission(it) }
                    .take(MAX_SIANO_TUNERS).map { it.deviceName }
            },
            openTuner = ::openUsbForTuner,
            openReader = ::openReaderForTuner,
            terrestrialChannels = { terrestrialSettings.active().channels },
            firmware = ::firmwareFile,
            px4Devices = ::permittedPx4Identities,
            openPx4 = ::openPx4ForDaemon,
            px4Firmware = ::ensurePx4Firmware,
            onStateChanged = ::publishStatus,
            onStartupResult = ::onSupervisorStartupResult
        )
        MirakcDiagnostics.triggerUpdateSchedules = {
            mirakcSupervisor?.triggerUpdateSchedules() == true
        }
        try {
            mirakcSupervisor?.start()
        } catch (error: Exception) {
            lastError = "upstream mirakc failed to start: ${error.message ?: error.javaClass.simpleName}"
        }
        initialized = true
        requestUsbPermissionIfNeeded()
        publishStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) onCreate()
        when (intent?.action) {
            ACTION_REQUEST_USB -> requestUsbPermissionIfNeeded()
            ACTION_APPLY_TERRESTRIAL -> applyTerrestrialSettings()
        }
        publishStatus()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        MirakcDiagnostics.triggerUpdateSchedules = null
        mirakcSupervisor?.stop()
        mirakcSupervisor = null
        if (receiverRegistered) {
            unregisterReceiver(usbPermissionReceiver)
            unregisterReceiver(usbLifecycleReceiver)
        }
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
        val permissionFilter = IntentFilter(USB_PERMISSION_ACTION)
        val lifecycleFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Permission responses are app-private; system USB lifecycle
            // broadcasts require an exported dynamic receiver on API 33+.
            registerReceiver(usbPermissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(usbLifecycleReceiver, lifecycleFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, permissionFilter)
            @Suppress("DEPRECATION")
            registerReceiver(usbLifecycleReceiver, lifecycleFilter)
        }
        receiverRegistered = true
    }

    private fun requestUsbPermissionIfNeeded() {
        val pending = (supportedDevices() + px4Devices() + readerDevices())
            .firstOrNull { !usbManager.hasPermission(it) }
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
        if (supportedDevices().isEmpty() && px4Devices().isEmpty()) {
            lastError = "No supported tuner found (Siano or PX-Q3U4)"
            publishStatus()
            return
        }
        lastError = "none"
        publishStatus()
    }

    private fun applyTerrestrialSettings() {
        var activated = false
        try {
            terrestrialSettings.activatePending()
            activated = true
            configurationApplyPending.set(true)
            val supervisor = mirakcSupervisor ?: throw IllegalStateException("mirakc supervisor is unavailable")
            supervisor.restartForConfiguration()
            lastError = "none"
        } catch (error: Exception) {
            configurationApplyPending.set(false)
            if (activated) {
                try {
                    terrestrialSettings.restoreLastKnownGood()
                } catch (rollbackError: Exception) {
                    lastError = "Terrestrial settings failed and rollback failed: ${rollbackError.message ?: rollbackError.javaClass.simpleName}"
                    publishStatus()
                    return
                }
            }
            lastError = "Terrestrial settings were not applied: ${error.message ?: error.javaClass.simpleName}"
        }
        publishStatus()
    }

    private fun onSupervisorStartupResult(success: Boolean): Boolean {
        if (!configurationApplyPending.compareAndSet(true, false)) return false
        if (success) {
            terrestrialSettings.commitApply()
            return true
        }
        try {
            terrestrialSettings.restoreLastKnownGood()
            lastError = "Terrestrial settings failed; restored the last-known-good configuration"
            mirakcSupervisor?.restartForConfiguration()
        } catch (error: Exception) {
            lastError = "Terrestrial settings failed and rollback failed: ${error.message ?: error.javaClass.simpleName}"
        }
        publishStatus()
        return true
    }

    private fun supportedDevices(): List<UsbDevice> = usbManager.deviceList.values.filter {
        (it.vendorId == 0x3275 && it.productId == 0x0080) ||
            (it.vendorId == 0x187f && (it.productId == 0x0600 || it.productId == 0x0302))
    }.sortedBy { it.deviceName }

    private fun px4Devices(): List<UsbDevice> = usbManager.deviceList.values.filter {
        it.vendorId == 0x0511 && it.productId == 0x084a
    }.sortedBy { it.deviceName }

    private fun permittedPx4Identities(): List<Px4DeviceIdentity> =
        px4Devices().filter { usbManager.hasPermission(it) }.mapNotNull { device ->
            val serial = try {
                device.serialNumber
            } catch (_: SecurityException) {
                null
            }
            serial?.let { Px4DeviceIdentity(device.deviceName, it) }
        }

    private fun isSmartCardReader(device: UsbDevice): Boolean {
        if (device.vendorId == 0x3275 || device.vendorId == 0x187f ||
            (device.vendorId == 0x0511 && device.productId == 0x084a)) return false
        if (device.vendorId == 0x04E6) return true
        if (device.deviceClass == 0x0B) return true
        for (index in 0 until device.interfaceCount) {
            if (device.getInterface(index).interfaceClass == 0x0B) return true
        }
        return false
    }

    private fun readerDevices(): List<UsbDevice> = usbManager.deviceList.values.filter(::isSmartCardReader)

    private fun openUsbForTuner(index: Int, deviceName: String): SianoUsbHandle {
        val device = supportedDevices().firstOrNull {
            it.deviceName == deviceName && usbManager.hasPermission(it)
        }
            ?: throw IOException("No permitted Siano tuner at index $index")
        val connection = usbManager.openDevice(device)
            ?: throw IOException("UsbManager.openDevice failed for ${device.deviceName}")
        val parcel = try {
            ParcelFileDescriptor.fromFd(connection.fileDescriptor)
        } catch (error: Exception) {
            connection.close()
            throw IOException("Unable to duplicate USB fd", error)
        }
        return SianoUsbHandle(parcel.fd) {
            parcel.close()
            connection.close()
        }
    }

    private fun openReaderForTuner(): SianoReaderHandle? {
        val device = readerDevices().firstOrNull { usbManager.hasPermission(it) } ?: return null
        val connection = usbManager.openDevice(device)
            ?: throw IOException("UsbManager.openDevice failed for ${device.deviceName}")
        val claimedInterfaces = mutableListOf<android.hardware.usb.UsbInterface>()
        try {
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                if (usbInterface.interfaceClass != 0x0B) continue
                if (!connection.claimInterface(usbInterface, true)) {
                    throw IOException("Unable to claim CCID interface ${usbInterface.id}")
                }
                claimedInterfaces += usbInterface
            }
            if (claimedInterfaces.isEmpty()) {
                throw IOException("No CCID interface on ${device.deviceName}")
            }
            val parcel = try {
                ParcelFileDescriptor.fromFd(connection.fileDescriptor)
            } catch (error: Exception) {
                throw IOException("Unable to duplicate reader fd", error)
            }
            return SianoReaderHandle(parcel.fd, parcel.fileDescriptor) {
                try {
                    // UsbDeviceConnection.close() normally releases claimed
                    // interfaces, but do it explicitly so every ownership
                    // path has a deterministic CCID release before teardown.
                    claimedInterfaces.asReversed().forEach { claimed ->
                        try { connection.releaseInterface(claimed) } catch (_: Exception) { }
                    }
                } finally {
                    try {
                        parcel.close()
                    } finally {
                        connection.close()
                    }
                }
            }
        } catch (error: Exception) {
            claimedInterfaces.asReversed().forEach { claimed ->
                try { connection.releaseInterface(claimed) } catch (_: Exception) { }
            }
            connection.close()
            throw error
        }
    }

    private fun openPx4ForDaemon(identity: Px4DeviceIdentity): Px4UsbHandle {
        val device = px4Devices().firstOrNull {
            if (it.deviceName != identity.deviceName || !usbManager.hasPermission(it)) return@firstOrNull false
            try {
                it.serialNumber == identity.serial
            } catch (_: SecurityException) {
                false
            }
        } ?: throw IOException("No permitted PX4 device at ${identity.deviceName}")
        val connection = usbManager.openDevice(device)
            ?: throw IOException("UsbManager.openDevice failed for ${device.deviceName}")
        val parcel = try {
            ParcelFileDescriptor.fromFd(connection.fileDescriptor)
        } catch (error: Exception) {
            connection.close()
            throw IOException("Unable to duplicate PX4 USB fd", error)
        }
        return Px4UsbHandle(parcel.fd, parcel) { connection.close() }
    }

    private fun firmwareFile(): File {
        val file = File(filesDir, "isdbt_rio.inp")
        if (!file.isFile || file.length() == 0L) {
            assets.open("isdbt_rio.inp").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    private fun ensurePx4Firmware(): File = px4FirmwareAcquirer.ensure()

    private fun publishStatus() {
        val device = supportedDevices().firstOrNull()
        val granted = device != null && usbManager.hasPermission(device)
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
            append("\nUpstream: ").append(mirakcSupervisor?.status() ?: "stopped")
            append("\nPX4: ").append(mirakcSupervisor?.px4Status() ?: "stopped")
            append("\nLast error: ").append(lastError)
        }
    }

    companion object {
        const val ACTION_REQUEST_USB = "dev.khronos31.mirakc.REQUEST_USB"
        const val ACTION_APPLY_TERRESTRIAL = "dev.khronos31.mirakc.APPLY_TERRESTRIAL"
        private const val TERRESTRIAL_SETTINGS = "terrestrial-channel-settings"
        private const val USB_PERMISSION_ACTION = "dev.khronos31.mirakc.USB_PERMISSION"
        private const val NOTIFICATION_CHANNEL = "mirakc-service"
        private const val NOTIFICATION_ID = 40772

        @Volatile
        var statusText: String = "Starting mirakc service..."

    }
}
