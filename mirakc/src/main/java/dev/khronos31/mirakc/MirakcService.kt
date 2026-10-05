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
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.content.pm.ServiceInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import dev.khronos31.updater.GitHubReleaseUpdater
import dev.khronos31.mirakc.ui.contract.AboutUi
import dev.khronos31.mirakc.ui.contract.MirakcUiAction
import dev.khronos31.mirakc.ui.contract.MirakcUiState
import dev.khronos31.mirakc.ui.contract.PreparedChannelsUi
import dev.khronos31.mirakc.ui.contract.RuntimePhase
import dev.khronos31.mirakc.ui.contract.ScanPhase
import dev.khronos31.mirakc.ui.contract.ScanProgressUi
import dev.khronos31.mirakc.ui.contract.SettingsUi
import dev.khronos31.mirakc.ui.contract.TunerDeviceUi
import dev.khronos31.mirakc.ui.contract.UiCapabilities
import dev.khronos31.mirakc.ui.contract.UpdateSuccessNotice
import dev.khronos31.mirakc.ui.contract.VersionRowUi

class MirakcService : Service() {
    private val controllerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mirakc-ui-controller").apply { isDaemon = true }
    }
    private val operations = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mirakc-ui-operation").apply { isDaemon = true }
    }
    private val hostHandler = Handler(Looper.getMainLooper())
    private val permissionContinuation = ScanPermissionContinuation()
    private val runtimeAdmissionGate = RuntimeAdmissionRetryGate()
    private val requestedScanId = java.util.concurrent.atomic.AtomicLong(0L)
    private val scanIdSequence = java.util.concurrent.atomic.AtomicLong(0L)
    private val scanHandoff = ScanRuntimeHandoff()
    private val serviceLock = Any()
    @Volatile private var pendingScanId = 0L
    @Volatile private var runtimeOwner: MirakcProcessRuntimeOwner.Owner? = null
    @Volatile private var runtimeAdmissionReady = false
    @Volatile private var serviceDestroyed = false
    @Volatile private var nativeScanStartedFor: Long? = null
    private var ownerAcquisitionThread: Thread? = null
    private var runtimeAdmissionRetryRequested = false
    @Volatile private var runtimeAdmissionFailed = false
    private var pendingScanStatus: GrScanStatus? = null
    @Volatile private var updateCheckInProgress = false
    @Volatile private var updateSuccessNotice: UpdateSuccessNotice? = null
    @Volatile private var lastError = "none"
    private val aboutSnapshot: AboutUi by lazy { aboutUi() }
    private lateinit var uiController: MirakcUiController
    private val localBinder = LocalBinder()
    private data class UsbPermissionEvent(
        val scanId: Long,
        val decision: ScanPermissionContinuation.Decision,
        val device: UsbDevice?,
        val granted: Boolean
    )

    internal inner class LocalBinder : Binder() {
        internal fun controller(): MirakcUiController = uiController
    }

    private val controllerRefresh = object : Runnable {
        override fun run() {
            if (::uiController.isInitialized) uiController.refresh()
            hostHandler.postDelayed(this, UI_REFRESH_INTERVAL_MS)
        }
    }
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val terrestrialSettings by lazy {
        TerrestrialChannelSettingsStore(
            AndroidStringSettings(getSharedPreferences(TERRESTRIAL_SETTINGS, MODE_PRIVATE))
        )
    }
    private val epgInterval by lazy {
        EpgUpdateIntervalStore(AndroidStringSettings(getSharedPreferences(EPG_SETTINGS, MODE_PRIVATE)))
    }
    private val configurationApplyPending = AtomicBoolean(false)
    @Volatile private var mirakcSupervisor: MirakcSupervisor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false
    private var initialized = false
    private val px4FirmwareAcquirer by lazy {
        Px4FirmwareAcquirer(
            destinationDirectory = { getExternalFilesDir(null) }
        )
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != USB_PERMISSION_ACTION) return
            val responseScanId = intent.getLongExtra(USB_PERMISSION_SCAN_ID, 0L)
            val responseOwnerToken = intent.getStringExtra(USB_PERMISSION_OWNER_TOKEN)
            val owner = runtimeOwner ?: return
            if (responseOwnerToken != owner.token || !isCurrentOwner(owner)) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val event = synchronized(serviceLock) {
                if (!isCurrentOwner(owner)) return@synchronized null
                val scanId = if (permissionContinuation.isPending) pendingScanId else 0L
                if (responseScanId != scanId) return@synchronized null
                val decision = if (scanId != 0L) {
                    val missing = if (granted && device != null) {
                        scanPermissionDevices().filterNot(usbManager::hasPermission).map { it.deviceName }
                    } else emptyList()
                    permissionContinuation.onPermissionResult(scanId, granted && device != null, missing)
                } else ScanPermissionContinuation.Decision.NotPending
                if (scanId != 0L && decision == ScanPermissionContinuation.Decision.Denied) {
                    scanHandoff.finish(scanId)
                    requestedScanId.compareAndSet(scanId, 0L)
                    pendingScanStatus = GrScanStatus.failed(scanId, "USB_PERMISSION_DENIED")
                    lastError = "USB権限が許可されませんでした"
                } else if (scanId == 0L && !granted) {
                    lastError = "USB権限が許可されませんでした"
                }
                UsbPermissionEvent(scanId, decision, device, granted)
            } ?: return
            if (granted && device != null) {
                val stillCurrent = synchronized(serviceLock) {
                    if (!isCurrentOwner(owner) || (event.scanId != 0L && pendingScanId != event.scanId)) {
                        false
                    } else {
                        lastError = "none"
                        statusText = "USB permission granted: ${device.deviceName}"
                        true
                    }
                }
                if (!stillCurrent) return
                val requestStillCurrent = event.scanId == 0L || synchronized(serviceLock) {
                    pendingScanId == event.scanId && isCurrentOwner(owner) &&
                        scanHandoff.ownsPreparation(event.scanId)
                }
                if (!requestStillCurrent) return
                if (MirakcUsbEventPolicy.requiresMirakcReconfigureOnPermissionGrant(
                    device.vendorId, device.productId, isSmartCardReader(device)
                )) {
                    mirakcSupervisor?.reconfigure()
                }
                if (event.scanId != 0L) {
                    handleScanPermissionDecision(event.decision, event.scanId)
                } else {
                    requestUsbPermissionIfNeeded()
                }
            } else {
                publishStatus()
                refreshUiController()
            }
        }
    }

    private val usbLifecycleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
                intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val owner = runtimeOwner ?: return
            if (!isCurrentOwner(owner)) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: run {
                publishStatus()
                return
            }
            val isAttached = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
            val isCcidReader = isSmartCardReader(device)
            if (!isAttached && MirakcUsbEventPolicy.isPx4Tuner(device.vendorId, device.productId)) {
                mirakcSupervisor?.invalidatePx4Device(device.deviceName)
            }
            if (isAttached && isUsbPermissionTarget(device)) requestUsbPermissionIfNeeded()
            if (MirakcUsbEventPolicy.requiresMirakcReconfigureOnLifecycle(
                    device.vendorId,
                    device.productId,
                    isCcidReader,
                    isAttached,
                    usbManager.hasPermission(device)
                )) {
                mirakcSupervisor?.reconfigure()
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
        uiController = MirakcUiController(
            runtime = AndroidRuntimePort(),
            worker = controllerExecutor
        )
        initialized = true
        hostHandler.post(controllerRefresh)
        publishStatus()
        beginRuntimeAdmission()
    }

    private fun beginRuntimeAdmission() {
        val thread = synchronized(serviceLock) {
            if (!runtimeAdmissionGate.beginAttempt()) return
            runtimeAdmissionFailed = false
            if (lastError != "none") lastError = "none"
            Thread({ runRuntimeAdmission() }, "mirakc-service-owner-admission").apply {
                isDaemon = true
                ownerAcquisitionThread = this
            }
        }
        refreshUiController()
        thread.start()
    }

    private fun requestRuntimeAdmissionRetry() {
        val startNow = synchronized(serviceLock) {
            if (serviceDestroyed || runtimeAdmissionReady) {
                false
            } else if (ownerAcquisitionThread?.isAlive == true) {
                runtimeAdmissionRetryRequested = true
                false
            } else {
                true
            }
        }
        if (startNow) beginRuntimeAdmission()
    }

    private fun runRuntimeAdmission() {
        try {
            val owner = try {
                PROCESS_RUNTIME_OWNER.acquireOwner()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            val lease = synchronized(serviceLock) {
                if (serviceDestroyed) {
                    PROCESS_RUNTIME_OWNER.beginStop(owner, PROCESS_CLEANUP_EXECUTOR) { }
                    return
                }
                runtimeOwner = owner
                PROCESS_RUNTIME_OWNER.acquireWork(owner)
            } ?: run {
                PROCESS_RUNTIME_OWNER.beginStop(owner, PROCESS_CLEANUP_EXECUTOR) { }
                return
            }
            var supervisorForCleanup: MirakcSupervisor? = null
            try {
                // Shared preferences, scan-state recovery, and native-owner
                // construction happen only after the previous Service has
                // completed its process-wide teardown barrier.
                terrestrialSettings.recoverIncompleteApply()
                recoverInterruptedScanState()
                val supervisor = createSupervisor(owner)
                supervisorForCleanup = supervisor
                val publish = synchronized(serviceLock) {
                    if (serviceDestroyed || !PROCESS_RUNTIME_OWNER.isCurrent(owner)) {
                        false
                    } else {
                        mirakcSupervisor = supervisor
                        runtimeAdmissionReady = true
                        runtimeAdmissionGate.markReady()
                        true
                    }
                }
                if (!publish) {
                    supervisor.stop()
                    if (!supervisor.awaitQuiescent(SUPERVISOR_QUIESCENCE_TIMEOUT_MS)) {
                        throw IllegalStateException("mirakc supervisor did not reach quiescence")
                    }
                    return
                }
                MirakcDiagnostics.triggerUpdateSchedules = {
                    isCurrentOwner(owner) && mirakcSupervisor?.triggerUpdateSchedules() == true
                }
                grScanStatusProvider = { if (isCurrentOwner(owner)) mirakcSupervisor?.grScanStatus() else null }
                statusSnapshotProvider = {
                    if (isCurrentOwner(owner)) {
                        publishStatus()
                        statusText
                    } else "mirakc service is stopping"
                }
                hostHandler.post {
                    if (!isCurrentOwner(owner)) return@post
                    if (requestedPublicRunning() && isSetupPrepared()) mirakcSupervisor?.start()
                    publishStatus()
                    refreshUiController()
                }
            } catch (error: Exception) {
                synchronized(serviceLock) {
                    runtimeAdmissionReady = false
                    runtimeAdmissionFailed = true
                    runtimeAdmissionGate.markFailed()
                    lastError = "mirakcの準備に失敗しました。画面を開き直してください"
                }
                android.util.Log.e(TAG, "runtime owner initialization failed", error)
                PROCESS_RUNTIME_OWNER.beginStop(owner, PROCESS_CLEANUP_EXECUTOR) {
                    supervisorForCleanup?.let {
                        it.stop()
                        if (!it.awaitQuiescent(SUPERVISOR_QUIESCENCE_TIMEOUT_MS)) {
                            throw IllegalStateException("mirakc supervisor did not reach quiescence")
                        }
                    }
                    if (runtimeOwner == owner) {
                        MirakcDiagnostics.triggerUpdateSchedules = null
                        grScanStatusProvider = null
                        statusSnapshotProvider = null
                        mirakcSupervisor = null
                    }
                }
                hostHandler.post { refreshUiController() }
                stopSelf()
            } finally {
                lease.close()
            }
        } finally {
            val retry = synchronized(serviceLock) {
                if (ownerAcquisitionThread === Thread.currentThread()) ownerAcquisitionThread = null
                if (!runtimeAdmissionReady && !serviceDestroyed) runtimeAdmissionGate.markFailed()
                val shouldRetry = runtimeAdmissionRetryRequested && !runtimeAdmissionReady && !serviceDestroyed
                runtimeAdmissionRetryRequested = false
                shouldRetry
            }
            if (retry) beginRuntimeAdmission()
        }
    }

    private fun createSupervisor(owner: MirakcProcessRuntimeOwner.Owner) = MirakcSupervisor(
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
        onStateChanged = { if (isCurrentOwner(owner)) onSupervisorStateChanged(owner) },
        onStartupResult = { success ->
            if (isCurrentOwner(owner)) onSupervisorStartupResult(success) else false
        },
        onGrScanStatus = { status ->
            if (isCurrentOwner(owner)) registerCompletedGrScan(status)
        }
    )

    private fun recoverInterruptedScanState() {
        val stateFile = File(filesDir, "mirakc-scan-runtime/cache/.gr-scan-state")
        recoverPersistedGrScanState(stateFile, MAX_SCAN_STATUS_BYTES)
    }

    private fun isCurrentOwner(owner: MirakcProcessRuntimeOwner.Owner): Boolean =
        !serviceDestroyed && runtimeOwner == owner && PROCESS_RUNTIME_OWNER.isCurrent(owner)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) onCreate()
        when (intent?.action) {
            ACTION_HOST, null -> {
                PROCESS_RUNTIME_OWNER.retryBlockedCleanup()
                if (!runtimeAdmissionReady) requestRuntimeAdmissionRetry()
                if (requestedPublicRunning() && isSetupPrepared()) mirakcSupervisor?.start()
            }
            ACTION_REQUEST_USB -> uiController.dispatch(MirakcUiAction.RequestUsbPermission)
            ACTION_APPLY_TERRESTRIAL -> uiController.dispatch(
                MirakcUiAction.SaveChannels(TerrestrialChannelSettings.inputText(terrestrialSettings.inputState().channels))
            )
            ACTION_START_SERVER -> uiController.dispatch(MirakcUiAction.StartServer)
            ACTION_STOP_SERVER -> uiController.dispatch(MirakcUiAction.StopServer)
            ACTION_SCAN_GR -> uiController.dispatch(MirakcUiAction.StartScan)
            ACTION_CANCEL_GR_SCAN -> uiController.dispatch(MirakcUiAction.CancelScan)
        }
        publishStatus()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onDestroy() {
        val owner: MirakcProcessRuntimeOwner.Owner?
        val supervisor: MirakcSupervisor?
        synchronized(serviceLock) {
            serviceDestroyed = true
            runtimeAdmissionReady = false
            runtimeAdmissionRetryRequested = false
            runtimeAdmissionGate.close()
            owner = runtimeOwner
            supervisor = mirakcSupervisor
            permissionContinuation.processStopped()
            scanHandoff.cancel(pendingScanId)
            requestedScanId.set(0L)
            pendingScanStatus = null
        }
        ownerAcquisitionThread?.interrupt()
        hostHandler.removeCallbacks(controllerRefresh)
        if (::uiController.isInitialized) uiController.close()
        controllerExecutor.shutdownNow()
        operations.shutdownNow()
        if (receiverRegistered) {
            unregisterReceiver(usbPermissionReceiver)
            unregisterReceiver(usbLifecycleReceiver)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        statusText = "Stopped"
        if (owner != null) {
            PROCESS_RUNTIME_OWNER.beginStop(owner, PROCESS_CLEANUP_EXECUTOR) {
                supervisor?.let {
                    it.stop()
                    if (!it.awaitQuiescent(SUPERVISOR_QUIESCENCE_TIMEOUT_MS)) {
                        throw IllegalStateException("mirakc supervisor did not reach quiescence")
                    }
                }
                if (runtimeOwner == owner) {
                    MirakcDiagnostics.triggerUpdateSchedules = null
                    grScanStatusProvider = null
                    statusSnapshotProvider = null
                }
            }
        }
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
            .setContentText("mirakc is managed from the app")
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
        val permissionFilter = IntentFilter(USB_PERMISSION_ACTION).apply {
            // PendingIntent identity uses a URI to distinguish owner/device/scan
            // requests. IntentFilter without a data scheme matches only intents
            // without data, so the Android USB permission response would never
            // reach this receiver unless its data URI is accepted here too.
            addDataScheme(USB_PERMISSION_URI_SCHEME)
            addDataAuthority(USB_PERMISSION_URI_AUTHORITY, null)
        }
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
        val pending = scanPermissionDevices().firstOrNull { !usbManager.hasPermission(it) }
        if (pending != null) {
            val owner = runtimeOwner ?: return
            requestUsbPermission(pending, owner, scanId = 0L)
            statusText = "USB permission dialog requested for ${pending.deviceName}"
            publishStatus()
            return
        }
        if (supportedDevices().isEmpty() && px4Devices().isEmpty()) {
            lastError = "No supported tuner found (Siano or supported PX4 device)"
            publishStatus()
            return
        }
        lastError = "none"
        publishStatus()
    }

    private fun applyTerrestrialSettings() {
        var activated = false
        val hasPendingSettings = terrestrialSettings.snapshot().pending != null
        try {
            if (hasPendingSettings) {
                terrestrialSettings.activatePending()
                activated = true
            }
            configurationApplyPending.set(activated)
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

    private fun registerCompletedGrScan(status: GrScanStatus) {
        val owner = runtimeOwner ?: return
        val lease = PROCESS_RUNTIME_OWNER.acquireWork(owner) ?: return
        try {
            synchronized(serviceLock) {
                if (!isCurrentOwner(owner) || status.scanId != pendingScanId) return
                var registrationSucceeded = true
                var terminalUiStatus = status
                try {
                    if (status.state == GrScanStatus.State.COMPLETE && status.isApplicable) {
                        terrestrialSettings.completeScan(status.scanId, status.foundChannels, allowEmptySetup = false)
                    } else if (status.state == GrScanStatus.State.EMPTY) {
                        // A first empty scan is ready only when bundled satellite
                        // channels can still be used. An empty rescan keeps the good
                        // configuration but records this scan to clear stale status.
                        terrestrialSettings.completeScan(
                            status.scanId,
                            emptyList(),
                            allowEmptySetup = hasSatelliteTuner()
                        )
                    } else if (status.state == GrScanStatus.State.FAILED) {
                        lastError = "地上波スキャンに失敗しました。既存のチャンネル設定は維持します"
                    } else if (status.state == GrScanStatus.State.INTERRUPTED) {
                        lastError = "地上波スキャンを中断しました。既存のチャンネル設定は維持します"
                    }
                } catch (error: Exception) {
                    registrationSucceeded = false
                    terminalUiStatus = GrScanStatus.failed(status.scanId, "SAVE_FAILED")
                    lastError = "スキャン結果を保存できませんでした。既存のチャンネル設定は維持されています"
                }
                // The settings transaction and terminal UI/handoff transition are
                // one short serviceLock critical section. onDestroy cannot revoke
                // this generation between validation and its durable commit.
                scanHandoff.finish(status.scanId)
                nativeScanStartedFor = null
                pendingScanStatus = when {
                    !registrationSucceeded -> terminalUiStatus
                    status.state == GrScanStatus.State.FAILED || status.state == GrScanStatus.State.INTERRUPTED -> status
                    else -> null
                }
                if (registrationSucceeded &&
                    (status.state == GrScanStatus.State.COMPLETE || status.state == GrScanStatus.State.EMPTY)
                ) {
                    lastError = "none"
                }
            }
        } finally {
            lease.close()
        }
        publishStatus()
        refreshUiController()
    }

    private fun supportedDevices(): List<UsbDevice> = usbManager.deviceList.values.filter {
        (it.vendorId == 0x3275 && it.productId == 0x0080) ||
            (it.vendorId == 0x187f && (it.productId == 0x0600 || it.productId == 0x0302))
    }.sortedBy { it.deviceName }

    private fun px4Devices(): List<UsbDevice> = usbManager.deviceList.values.filter {
        it.vendorId == 0x0511 && it.productId in PX4_PRODUCT_IDS
    }.sortedBy { it.deviceName }

    private fun permittedPx4Identities(): List<Px4DeviceIdentity> =
        px4Devices().filter { usbManager.hasPermission(it) }.mapNotNull { device ->
            val serial = try {
                device.serialNumber
            } catch (_: SecurityException) {
                null
            }
            serial?.let { Px4DeviceIdentity(device.deviceName, it, device.productId) }
        }

    private fun isSmartCardReader(device: UsbDevice): Boolean {
        if (device.vendorId == 0x3275 || device.vendorId == 0x187f ||
            (device.vendorId == 0x0511 && device.productId in PX4_PRODUCT_IDS)) return false
        if (device.vendorId == 0x04E6) return true
        if (device.deviceClass == 0x0B) return true
        for (index in 0 until device.interfaceCount) {
            if (device.getInterface(index).interfaceClass == 0x0B) return true
        }
        return false
    }

    private fun readerDevices(): List<UsbDevice> = usbManager.deviceList.values.filter(::isSmartCardReader)

    private fun isUsbPermissionTarget(device: UsbDevice): Boolean =
        MirakcUsbEventPolicy.affectsTunerConfiguration(device.vendorId, device.productId) ||
            isSmartCardReader(device)

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
            if (it.deviceName != identity.deviceName || it.productId != identity.productId ||
                !usbManager.hasPermission(it)) return@firstOrNull false
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
            append("\nListener: ").append(mirakcSupervisor?.status()?.let { status ->
                when {
                    status.contains("scan runtime") || status.contains("scan-") -> "127.0.0.1:40773 (scan only)"
                    status.contains("running (pid") || status.contains("starting public") || status.contains("probing") ->
                        "0.0.0.0:40772"
                    else -> "stopped"
                }
            } ?: "stopped")
            append("\nUpstream: ").append(mirakcSupervisor?.status() ?: "stopped")
            append("\nPX4: ").append(mirakcSupervisor?.px4Status() ?: "stopped")
            append("\nLast error: ").append(lastError)
        }
    }

    private fun startGrScan() {
        val owner = runtimeOwner
        if (!runtimeAdmissionReady || owner == null || !isCurrentOwner(owner)) {
            throw MirakcUiOperationException("mirakcの管理機能を準備しています。少し待ってから再試行してください")
        }
        val supervisor = mirakcSupervisor ?: throw MirakcUiOperationException("mirakcの管理機能を初期化できませんでした")
        val observed = supervisor.snapshot()
        if (observed.phase !in setOf(MirakcObservedPhase.STOPPED, MirakcObservedPhase.ERROR)) {
            throw MirakcUiOperationException("チャンネルスキャンの前にmirakcを停止してください")
        }
        val scanId = scanIdSequence.updateAndGet { previous ->
            maxOf(System.currentTimeMillis(), previous + 1L, 1L)
        }
        val decision = synchronized(serviceLock) {
            if (!isCurrentOwner(owner)) throw MirakcUiOperationException("mirakcの停止処理中です")
            if (!scanHandoff.begin(scanId)) {
                throw MirakcUiOperationException("別のスキャン処理が終了するまでお待ちください")
            }
            pendingScanId = scanId
            requestedScanId.set(scanId)
            pendingScanStatus = GrScanStatus.queued(pendingScanId)
            permissionContinuation.begin(
                scanId,
                scanPermissionDevices().filterNot(usbManager::hasPermission).map { it.deviceName }
            )
        }
        handleScanPermissionDecision(decision, scanId)
    }

    private fun cancelGrScan() {
        val (scanId, decision) = synchronized(serviceLock) {
            val scanId = pendingScanId
            val outcome = scanHandoff.cancel(scanId)
            if (outcome == ScanRuntimeHandoff.CancelDecision.CanceledPreparation) {
                permissionContinuation.cancel(scanId)
                requestedScanId.compareAndSet(scanId, 0L)
                pendingScanStatus = GrScanStatus.interrupted(scanId)
                lastError = "none"
            } else if (outcome is ScanRuntimeHandoff.CancelDecision.CancelNative) {
                requestedScanId.compareAndSet(scanId, 0L)
                nativeScanStartedFor = null
            }
            scanId to outcome
        }
        when (decision) {
            ScanRuntimeHandoff.CancelDecision.CanceledPreparation -> Unit
            is ScanRuntimeHandoff.CancelDecision.CancelNative -> {
                val supervisor = mirakcSupervisor
                val statusBeforeStop = supervisor?.grScanStatus()
                if (supervisor?.cancelGrScan() != true) {
                    synchronized(serviceLock) {
                        val owner = runtimeOwner
                        if (pendingScanId == scanId && owner != null && isCurrentOwner(owner)) {
                            scanHandoff.restoreNativeAfterCancelFailure(scanId)
                            nativeScanStartedFor = scanId
                        }
                    }
                    throw MirakcUiOperationException("スキャン停止を受け付けられませんでした")
                }
                val stoppedStatus = supervisor.grScanStatus()
                synchronized(serviceLock) {
                    if (pendingScanId == scanId) {
                        scanHandoff.finish(scanId)
                        pendingScanStatus = GrScanStatus.canceledFromNative(
                            scanId = scanId,
                            stoppedStatus = stoppedStatus,
                            statusBeforeStop = statusBeforeStop
                        )
                        lastError = "none"
                    }
                }
            }
            ScanRuntimeHandoff.CancelDecision.AlreadyFinishing ->
                throw MirakcUiOperationException("スキャン結果を保存しています")
            ScanRuntimeHandoff.CancelDecision.NotCancelable ->
                throw MirakcUiOperationException("キャンセルできるスキャンがありません")
        }
        publishStatus()
    }

    private fun handleScanPermissionDecision(decision: ScanPermissionContinuation.Decision, scanId: Long) {
        when (decision) {
            is ScanPermissionContinuation.Decision.RequestPermission -> {
                val owner = runtimeOwner ?: return
                val device = synchronized(serviceLock) {
                    if (pendingScanId != scanId || !isCurrentOwner(owner) ||
                        !scanHandoff.ownsPreparation(scanId)
                    ) return
                    scanPermissionDevices().firstOrNull { it.deviceName == decision.deviceId }.also { found ->
                        if (found == null) {
                            permissionContinuation.cancel(scanId)
                            scanHandoff.finish(scanId)
                            requestedScanId.compareAndSet(scanId, 0L)
                            pendingScanStatus = GrScanStatus.failed(scanId, "TUNER_REMOVED")
                            lastError = "USBチューナーが取り外されました"
                        } else lastError = "USB権限を確認しています"
                    }
                }
                if (device != null) requestUsbPermission(device, owner, scanId)
            }
            ScanPermissionContinuation.Decision.StartScan -> {
                val owner = runtimeOwner
                val admitted = synchronized(serviceLock) {
                    if (pendingScanId != scanId || owner == null || !isCurrentOwner(owner) ||
                        !scanHandoff.ownsPreparation(scanId)
                    ) false else {
                        lastError = "none"
                        true
                    }
                }
                if (admitted) operations.execute { startScanRuntime(scanId) }
            }
            ScanPermissionContinuation.Decision.Denied -> {
                synchronized(serviceLock) {
                    val owner = runtimeOwner
                    if (pendingScanId == scanId && owner != null && isCurrentOwner(owner)) {
                        lastError = "USB権限が許可されませんでした"
                        scanHandoff.finish(scanId)
                        requestedScanId.compareAndSet(scanId, 0L)
                        pendingScanStatus = GrScanStatus.failed(scanId, "USB_PERMISSION_DENIED")
                    }
                }
            }
            ScanPermissionContinuation.Decision.Canceled -> {
                synchronized(serviceLock) {
                    val owner = runtimeOwner
                    if (pendingScanId == scanId && owner != null && isCurrentOwner(owner)) {
                        lastError = "スキャンをキャンセルしました"
                        scanHandoff.finish(scanId)
                        requestedScanId.compareAndSet(scanId, 0L)
                        pendingScanStatus = GrScanStatus.interrupted(scanId)
                    }
                }
            }
            ScanPermissionContinuation.Decision.NotPending -> Unit
        }
        publishStatus()
        refreshUiController()
    }

    private fun startScanRuntime(scanId: Long) {
        val owner = runtimeOwner ?: return
        val lease = PROCESS_RUNTIME_OWNER.acquireWork(owner) ?: return
        try {
            if (!isCurrentOwner(owner)) return
            startScanRuntimeOwned(scanId, owner)
        } finally {
            lease.close()
        }
    }

    private fun startScanRuntimeOwned(scanId: Long, owner: MirakcProcessRuntimeOwner.Owner) {
        val supervisor = mirakcSupervisor ?: return
        try {
            val requestCurrent = synchronized(serviceLock) {
                isCurrentOwner(owner) && pendingScanId == scanId &&
                    requestedScanId.get() == scanId && scanHandoff.ownsPreparation(scanId)
            }
            if (!requestCurrent) return
            if (!hasGrantedTerrestrialTuner()) {
                if (hasSatelliteTuner()) {
                    px4FirmwareAcquirer.ensure()
                    val committed = synchronized(serviceLock) {
                        if (!isCurrentOwner(owner) || pendingScanId != scanId ||
                            requestedScanId.get() != scanId || !scanHandoff.claimPreparedCommit(scanId)
                        ) false else {
                            terrestrialSettings.completeScan(scanId, emptyList(), allowEmptySetup = true)
                            requestedScanId.compareAndSet(scanId, 0L)
                            pendingScanStatus = null
                            lastError = "地上波対応チューナーがありません。BS/CSチャンネルを準備しました"
                            scanHandoff.finish(scanId)
                            true
                        }
                    }
                    if (!committed) return
                } else {
                    synchronized(serviceLock) {
                        if (isCurrentOwner(owner) && pendingScanId == scanId &&
                            scanHandoff.finish(scanId)
                        ) {
                            lastError = "地上波または衛星波に対応するチューナーがありません"
                            pendingScanStatus = GrScanStatus.failed(scanId, "NO_TUNER")
                            requestedScanId.compareAndSet(scanId, 0L)
                        }
                    }
                }
                publishStatus()
                refreshUiController()
                return
            }
            var stopFailedStart = false
            synchronized(serviceLock) {
                if (!isCurrentOwner(owner) || pendingScanId != scanId || requestedScanId.get() != scanId) return
                var runtimeStarted = false
                val requested = scanHandoff.handoffToNative(scanId) {
                    runtimeStarted = supervisor.startScanOnly()
                    runtimeStarted && supervisor.requestGrScan(scanId)
                }
                if (!requested) {
                    scanHandoff.finish(scanId)
                    lastError = "GRスキャンを開始できませんでした"
                    pendingScanStatus = GrScanStatus.failed(scanId, "SCAN_START_FAILED")
                    requestedScanId.compareAndSet(scanId, 0L)
                    stopFailedStart = runtimeStarted
                } else {
                    lastError = "none"
                    pendingScanStatus = null
                    nativeScanStartedFor = scanId
                    requestedScanId.compareAndSet(scanId, 0L)
                }
            }
            if (stopFailedStart) operations.execute { supervisor.stop() }
        } catch (error: Exception) {
            val ownsFailure = synchronized(serviceLock) {
                if (isCurrentOwner(owner) && pendingScanId == scanId && scanHandoff.finish(scanId)) {
                    lastError = "スキャン準備に失敗しました。USB接続とファームウェアを確認してください"
                    pendingScanStatus = GrScanStatus.failed(scanId, "PREPARATION_FAILED")
                    requestedScanId.compareAndSet(scanId, 0L)
                    true
                } else false
            }
            if (!ownsFailure) return
            android.util.Log.e(TAG, "GR scan preparation failed", error)
        }
        publishStatus()
        refreshUiController()
    }

    private fun scanPermissionDevices(): List<UsbDevice> = combineUsbPermissionTargets(
        supportedDevices() + px4Devices(),
        readerDevices(),
        UsbDevice::getDeviceName
    )

    private fun requestUsbPermission(
        device: UsbDevice,
        owner: MirakcProcessRuntimeOwner.Owner,
        scanId: Long
    ) {
        synchronized(serviceLock) {
            if (!isCurrentOwner(owner)) return
            if (scanId == 0L) {
                if (permissionContinuation.isPending || requestedScanId.get() != 0L) return
            } else if (pendingScanId != scanId || !scanHandoff.ownsPreparation(scanId)) {
                return
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                device.deviceId,
                Intent(USB_PERMISSION_ACTION)
                    .setPackage(packageName)
                    .setData(android.net.Uri.parse("$USB_PERMISSION_URI_SCHEME://$USB_PERMISSION_URI_AUTHORITY/${owner.token}/${device.deviceId}/$scanId"))
                    .putExtra(USB_PERMISSION_SCAN_ID, scanId)
                    .putExtra(USB_PERMISSION_OWNER_TOKEN, owner.token),
                flags
            )
            // Keep this short Android permission request inside the same lock as
            // cancellation/new-scan admission. The immutable scanId is never
            // recomputed from mutable Service state after validation.
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun onSupervisorStateChanged(owner: MirakcProcessRuntimeOwner.Owner) {
        // Supervisor/PX4 callbacks can run while their locks are held. Defer all
        // snapshot reads until after the callback returns to avoid lock inversion.
        hostHandler.post {
            if (!isCurrentOwner(owner)) return@post
            publishStatus()
            refreshUiController()
        }
    }

    private fun refreshUiController() {
        if (::uiController.isInitialized && !serviceDestroyed) runCatching { uiController.refresh() }
    }

    private inner class AndroidRuntimePort : MirakcRuntimePort {
        override fun snapshot(): MirakcUiState = snapshotForUi()

        override fun perform(action: MirakcUiAction): MirakcUiState {
            val owner = runtimeOwner
                ?: throw MirakcUiOperationException("mirakcの準備を待っています")
            val lease = PROCESS_RUNTIME_OWNER.acquireWork(owner)
                ?: throw MirakcUiOperationException("mirakcの停止処理中です。画面を更新してください")
            try {
                if (!isCurrentOwner(owner)) throw MirakcUiOperationException("mirakcの停止処理中です")
                val before = snapshotForUi()
                if (!actionAllowed(before.capabilities, action)) {
                    throw MirakcUiOperationException("操作できる状態ではありません。画面を更新して再試行してください")
                }
                when (action) {
                    MirakcUiAction.StartScan -> startGrScan()
                    MirakcUiAction.CancelScan -> cancelGrScan()
                    MirakcUiAction.StartServer -> startPublicServer()
                    MirakcUiAction.StopServer -> stopPublicServer()
                    MirakcUiAction.RequestUsbPermission -> requestUsbPermissionIfNeeded()
                    MirakcUiAction.CheckUpdates -> checkUpdates()
                    is MirakcUiAction.SaveEpgInterval -> epgInterval.save(action.input)
                    is MirakcUiAction.SaveChannels -> {
                        if (!isSetupPrepared() || mirakcSupervisor?.snapshot()?.phase != MirakcObservedPhase.STOPPED) {
                            throw MirakcUiOperationException("チャンネル編集は設定完了後、mirakc停止中に行えます")
                        }
                        terrestrialSettings.savePreparedChannels(action.input)
                    }
                }
                if (action is MirakcUiAction.SaveEpgInterval || action is MirakcUiAction.SaveChannels) {
                    lastError = "none"
                }
                return snapshotForUi()
            } catch (error: Exception) {
                val message = (error as? MirakcUiOperationException)?.message ?: when (action) {
                    is MirakcUiAction.SaveEpgInterval -> "番組表の更新間隔を確認してください"
                    is MirakcUiAction.SaveChannels -> "チャンネル番号を確認してください"
                    else -> "操作を完了できませんでした。状態を確認して再試行してください"
                }
                if (isCurrentOwner(owner)) lastError = message
                throw MirakcUiOperationException(message)
            } finally {
                lease.close()
            }
        }
    }

    private fun snapshotForUi(): MirakcUiState {
        if (!runtimeAdmissionReady) return connectingState()
        val observed = mirakcSupervisor?.snapshot()
        val persistedScan = observed?.scan
        val scan = synchronized(serviceLock) { pendingScanStatus } ?: persistedScan
        val phase = when {
            observed?.phase == MirakcObservedPhase.FINISHING_SCAN -> RuntimePhase.FINISHING_SCAN
            observed?.phase == MirakcObservedPhase.STOPPING -> RuntimePhase.STOPPING
            observed?.phase == MirakcObservedPhase.STARTING_SCAN -> RuntimePhase.PREPARING
            requestedScanId.get() != 0L && !permissionContinuation.isPending -> RuntimePhase.PREPARING
            scan?.state == GrScanStatus.State.RUNNING -> RuntimePhase.SCANNING
            scan?.state == GrScanStatus.State.QUEUED -> RuntimePhase.STARTING_SCAN
            observed == null -> RuntimePhase.IDLE
            else -> when (observed.phase) {
                MirakcObservedPhase.STOPPED -> RuntimePhase.IDLE
                MirakcObservedPhase.STARTING_SCAN -> RuntimePhase.PREPARING
                MirakcObservedPhase.SCAN_READY -> RuntimePhase.STARTING_SCAN
                MirakcObservedPhase.STARTING_PUBLIC, MirakcObservedPhase.RESTARTING -> RuntimePhase.STARTING_SERVER
                MirakcObservedPhase.RUNNING_PUBLIC -> RuntimePhase.RUNNING_SERVER
                MirakcObservedPhase.SCANNING -> RuntimePhase.SCANNING
                MirakcObservedPhase.FINISHING_SCAN -> RuntimePhase.FINISHING_SCAN
                MirakcObservedPhase.STOPPING -> RuntimePhase.STOPPING
                MirakcObservedPhase.ERROR -> RuntimePhase.ERROR
            }
        }
        val setupPrepared = isSetupPrepared()
        val devices = uiDevices()
        val scanUi = scan?.let(::toScanProgressUi)
        val publicActive = observed?.mode == MirakcRuntimeMode.PUBLIC && observed.phase !in setOf(
            MirakcObservedPhase.STOPPED, MirakcObservedPhase.ERROR
        )
        val scanActive = phase in setOf(
            RuntimePhase.WAITING_FOR_PERMISSION, RuntimePhase.PREPARING, RuntimePhase.STARTING_SCAN,
            RuntimePhase.SCANNING, RuntimePhase.FINISHING_SCAN, RuntimePhase.STOPPING
        ) && (permissionContinuation.isPending || requestedScanId.get() != 0L ||
            observed?.mode == MirakcRuntimeMode.SCAN_ONLY && observed.phase != MirakcObservedPhase.STOPPED ||
            scan?.isRunning == true)
        val recognizedPx4Plan = Px4DeviceSelector.plan(permittedPx4Identities())
        val hasPotentialScanTuner = supportedDevices().isNotEmpty() ||
            recognizedPx4Plan.tuners.isNotEmpty() || px4Devices().any { device ->
                Px4DeviceSelector.modelForProductId(device.productId) != null
            }
        val canScan = !publicActive && !scanActive && hasPotentialScanTuner
        val canCancelScan = scanActive &&
            phase !in setOf(RuntimePhase.FINISHING_SCAN, RuntimePhase.STOPPING) &&
            scanHandoff.canCancel(pendingScanId)
        val hasAnyMissingPermission = scanPermissionDevices().any { !usbManager.hasPermission(it) }
        val title = when {
            phase == RuntimePhase.FINISHING_SCAN -> "スキャン結果を保存しています"
            phase == RuntimePhase.STOPPING -> when (observed?.mode) {
                MirakcRuntimeMode.PUBLIC -> "mirakcを停止しています"
                MirakcRuntimeMode.SCAN_ONLY -> "スキャンを停止しています"
                null -> "停止しています"
            }
            phase == RuntimePhase.PREPARING -> "チューナーを準備しています"
            scan?.state == GrScanStatus.State.QUEUED -> "スキャン待機中"
            scan?.state == GrScanStatus.State.RUNNING -> "地上波チャンネルを探索しています"
            phase == RuntimePhase.STARTING_SERVER -> "mirakcを起動しています"
            phase == RuntimePhase.RUNNING_SERVER -> "mirakcが起動しています"
            phase == RuntimePhase.ERROR -> "操作を完了できませんでした"
            setupPrepared -> "チャンネル設定の準備ができています"
            else -> "チャンネルをスキャンして初期設定を行ってください"
        }
        val detail = when {
            phase == RuntimePhase.FINISHING_SCAN || phase == RuntimePhase.STOPPING -> observed?.detail.orEmpty()
            phase == RuntimePhase.PREPARING || phase == RuntimePhase.STARTING_SERVER -> observed?.detail.orEmpty()
            scan?.state == GrScanStatus.State.RUNNING -> scan.summary()
            scan?.state == GrScanStatus.State.QUEUED -> "スキャン開始を待っています"
            phase == RuntimePhase.RUNNING_SERVER -> "公開サーバーが実行中です"
            phase == RuntimePhase.ERROR -> lastError.takeUnless { it == "none" } ?: observed?.detail.orEmpty()
            setupPrepared -> "保存したチャンネル設定でmirakcを起動できます"
            else -> "保存済みの初期チャンネル設定はまだありません"
        }
        val prepared = if (setupPrepared) PreparedChannelsUi(
            terrestrialSettings.active().channels.map { it.number },
            PX4_SATELLITE_CHANNELS.count { it.type == Px4SatelliteChannelType.BS },
            PX4_SATELLITE_CHANNELS.count { it.type == Px4SatelliteChannelType.CS }
        ) else null
        val capabilities = UiCapabilities(
            canScan = canScan,
            canCancelScan = canCancelScan,
            canStartServer = setupPrepared && !publicActive && !scanActive &&
                (observed?.phase == MirakcObservedPhase.STOPPED || observed?.phase == MirakcObservedPhase.ERROR),
            canStopServer = publicActive || requestedPublicRunning(),
            canRequestUsbPermission = hasAnyMissingPermission && !scanActive,
            canEditSettings = setupPrepared && !publicActive && !scanActive,
            canCheckUpdates = !updateCheckInProgress
        )
        return MirakcUiState(
            phase = if (permissionContinuation.isPending) RuntimePhase.WAITING_FOR_PERMISSION else phase,
            statusTitle = if (permissionContinuation.isPending) "USB権限を確認しています" else title,
            statusDetail = if (permissionContinuation.isPending) "許可するとこのスキャンを続けます" else detail,
            devices = devices,
            scan = scanUi,
            prepared = prepared,
            capabilities = capabilities,
            settings = SettingsUi(
                terrestrialInput = TerrestrialChannelSettings.inputText(terrestrialSettings.inputState().channels),
                epgIntervalMinutes = epgInterval.minutes()
            ),
            about = aboutSnapshot,
            message = lastError.takeUnless { it == "none" },
            updateSuccessNotice = updateSuccessNotice
        )
    }

    private fun connectingState(): MirakcUiState {
        val cleanupFailed = PROCESS_RUNTIME_OWNER.blockingFailure() != null
        val admissionFailed = runtimeAdmissionFailed
        val detail = when {
            cleanupFailed -> "前のmirakc停止処理を完了できませんでした。安全確認が必要です"
            admissionFailed -> lastError
            lastError != "none" -> lastError
            else -> "前のmirakc処理が終了するまでお待ちください"
        }
        return MirakcUiState(
            phase = if (cleanupFailed || admissionFailed) RuntimePhase.ERROR else RuntimePhase.PREPARING,
            statusTitle = when {
                cleanupFailed -> "mirakcの復旧が必要です"
                admissionFailed -> "mirakcの準備に失敗しました"
                else -> "mirakcを準備しています"
            },
            statusDetail = detail,
            devices = emptyList(),
            scan = null,
            prepared = null,
            capabilities = UiCapabilities(),
            settings = SettingsUi("", EpgUpdateIntervalSettings.DEFAULT_MINUTES),
            about = aboutSnapshot,
            message = if (cleanupFailed || admissionFailed) detail else null,
            updateSuccessNotice = updateSuccessNotice
        )
    }

    private fun actionAllowed(capabilities: UiCapabilities, action: MirakcUiAction): Boolean = when (action) {
        MirakcUiAction.StartScan -> capabilities.canScan
        MirakcUiAction.CancelScan -> capabilities.canCancelScan
        MirakcUiAction.StartServer -> capabilities.canStartServer
        MirakcUiAction.StopServer -> capabilities.canStopServer
        MirakcUiAction.RequestUsbPermission -> capabilities.canRequestUsbPermission
        MirakcUiAction.CheckUpdates -> capabilities.canCheckUpdates
        is MirakcUiAction.SaveEpgInterval, is MirakcUiAction.SaveChannels -> capabilities.canEditSettings
    }

    private fun toScanProgressUi(status: GrScanStatus) = ScanProgressUi(
        operationId = status.scanId,
        phase = when (status.state) {
            GrScanStatus.State.QUEUED -> ScanPhase.WAITING
            GrScanStatus.State.RUNNING -> ScanPhase.RUNNING
            GrScanStatus.State.COMPLETE -> ScanPhase.COMPLETE
            GrScanStatus.State.EMPTY -> ScanPhase.EMPTY
            GrScanStatus.State.INTERRUPTED -> ScanPhase.CANCELED
            GrScanStatus.State.FAILED -> ScanPhase.FAILED
        },
        completed = status.completed,
        total = status.total,
        currentChannel = status.currentChannel,
        foundChannels = status.foundChannels,
        failedChannels = status.failedChannels
    )

    private fun uiDevices(): List<TunerDeviceUi> {
        val result = mutableListOf<TunerDeviceUi>()
        val detectedPx4Devices = px4Devices()
        supportedDevices().take(MAX_SIANO_TUNERS).forEach { device ->
            result += TunerDeviceUi(
                id = "siano:${device.deviceName}",
                displayName = "Siano ${device.productName ?: device.deviceName}",
                receiverCount = 1,
                supportsTerrestrial = true,
                supportsSatellite = false,
                permissionGranted = usbManager.hasPermission(device)
            )
        }
        val identities = permittedPx4Identities()
        val plan = Px4DeviceSelector.plan(identities)
        plan.enclosures.forEach { enclosure ->
            val count = enclosure.model.receiverCount
            val tuners = plan.tuners.filter { it.instanceToken == enclosure.instanceToken }
            result += TunerDeviceUi(
                id = enclosure.instanceToken,
                displayName = "PX4 ${enclosure.model.adapterArgument.uppercase()} ${enclosure.serial}",
                receiverCount = count,
                supportsTerrestrial = tuners.any { it.supportsTerrestrial },
                supportsSatellite = tuners.any { it.supportsSatellite },
                permissionGranted = enclosure.devices.all { identity ->
                    detectedPx4Devices.any { it.deviceName == identity.deviceName && usbManager.hasPermission(it) }
                }
            )
        }
        val mappedDeviceNames = plan.enclosures.flatMap { it.devices }.mapTo(hashSetOf()) { it.deviceName }
        Px4DeviceSelector.unmappedDetectedDevices(
            detectedPx4Devices.map { it.deviceName to it.productId },
            mappedDeviceNames
        ).map { detected ->
            val device = detectedPx4Devices.first { it.deviceName == detected.deviceName }
            val model = detected.model
            TunerDeviceUi(
                id = "px4-unmapped:${model.adapterArgument}:${device.deviceName}",
                displayName = "PX4 ${model.adapterArgument.uppercase()}",
                receiverCount = 0,
                supportsTerrestrial = (0 until model.receiverCount).any { model.capabilitiesFor(it)?.terrestrial == true },
                supportsSatellite = (0 until model.receiverCount).any { model.capabilitiesFor(it)?.satellite == true },
                permissionGranted = usbManager.hasPermission(device)
            )
        }.forEach(result::add)
        return result.toList()
    }

    private fun aboutUi(): AboutUi {
        val metadata = ApkSourceMetadata.readOrNull(assets)
        val app = metadata?.component("dtv-android")
        val engine = listOfNotNull(metadata?.component("mirakc"))
            .map { VersionRowUi(it.name, it.version) }
        val drivers = listOfNotNull(metadata?.component("siano-userland"), metadata?.component("px4-userland"))
            .map { VersionRowUi(it.name, it.version) }
        val unavailable = getString(R.string.about_metadata_unavailable)
        return AboutUi(
            appVersion = BuildConfig.VERSION_NAME.ifBlank { unavailable },
            engineVersions = engine,
            driverVersions = drivers,
            repositoryUrl = app?.url?.let(::normalizedRepositoryUrl).orEmpty().ifBlank { unavailable },
            licenseText = renderLicenseInventory(this, assets, metadata)
        )
    }

    private fun normalizedRepositoryUrl(raw: String): String? {
        val uri = android.net.Uri.parse(raw)
        if (uri.scheme != "https" || uri.host != "github.com" || uri.pathSegments.isEmpty()) return null
        return uri.buildUpon().path(uri.path?.removeSuffix(".git") ?: return null).build().toString().trimEnd('/')
    }

    private fun checkUpdates() {
        val owner = runtimeOwner ?: throw MirakcUiOperationException("mirakcの準備を待っています")
        if (!isCurrentOwner(owner)) throw MirakcUiOperationException("mirakcの停止処理中です")
        if (updateCheckInProgress) throw MirakcUiOperationException("アップデート確認はすでに実行中です")
        updateCheckInProgress = true
        refreshUiController()
        GitHubReleaseUpdater(this, "mirakc", "dev.khronos31.mirakc").check { result ->
            if (!isCurrentOwner(owner)) return@check
            updateCheckInProgress = false
            when (result) {
                is GitHubReleaseUpdater.CheckResult.UpToDate -> {
                    updateSuccessNotice = UpdateSuccessNotice(
                        eventId = updateNoticeSequence.incrementAndGet(),
                        text = "アプリは最新です"
                    )
                    lastError = "none"
                }
                is GitHubReleaseUpdater.CheckResult.UpdateAvailable ->
                    lastError = "アップデートがあります (${result.update.versionText})"
                is GitHubReleaseUpdater.CheckResult.Failure ->
                    lastError = "アップデートを確認できませんでした"
            }
            refreshUiController()
        }
    }

    private fun startPublicServer() {
        if (!isSetupPrepared()) {
            lastError = "Channel scan must complete before starting mirakc"
            return
        }
        if (!getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(REQUESTED_PUBLIC_RUNNING_KEY, true).commit()
        ) {
            lastError = "Could not save requested server state"
            return
        }
        if (mirakcSupervisor?.start() == true) lastError = "none" else {
            getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(REQUESTED_PUBLIC_RUNNING_KEY, false).commit()
            lastError = "Stop the scan runtime before starting mirakc"
        }
    }

    private fun stopPublicServer() {
        getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit()
            .putBoolean(REQUESTED_PUBLIC_RUNNING_KEY, false).commit()
        mirakcSupervisor?.stop()
        lastError = "none"
    }

    private fun requestedPublicRunning(): Boolean = getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE)
        .getBoolean(REQUESTED_PUBLIC_RUNNING_KEY, false)

    private fun isSetupPrepared(): Boolean = terrestrialSettings.isSetupPrepared()

    private fun hasSatelliteTuner(): Boolean = permittedPx4Identities().any { identity ->
        val model = Px4DeviceSelector.modelForProductId(identity.productId) ?: return@any false
        (0 until model.receiverCount).any { model.capabilitiesFor(it)?.satellite == true }
    }

    private fun hasGrantedTerrestrialTuner(): Boolean = supportedDevices().any(usbManager::hasPermission) ||
        Px4DeviceSelector.plan(permittedPx4Identities()).tuners.any { it.supportsTerrestrial }

    private fun hasUnpermittedTerrestrialTuner(): Boolean = supportedDevices().any { !usbManager.hasPermission(it) } ||
        px4Devices().any { device ->
            if (usbManager.hasPermission(device)) return@any false
            val model = Px4DeviceSelector.modelForProductId(device.productId) ?: return@any false
            (0 until model.receiverCount).any { model.capabilitiesFor(it)?.terrestrial == true }
        }

    private fun hasAttachedSupportedTuner(): Boolean = supportedDevices().isNotEmpty() || px4Devices().isNotEmpty()

    companion object {
        const val ACTION_HOST = "dev.khronos31.mirakc.HOST"
        const val ACTION_REQUEST_USB = "dev.khronos31.mirakc.REQUEST_USB"
        const val ACTION_APPLY_TERRESTRIAL = "dev.khronos31.mirakc.APPLY_TERRESTRIAL"
        const val ACTION_SCAN_GR = "dev.khronos31.mirakc.SCAN_GR"
        const val ACTION_CANCEL_GR_SCAN = "dev.khronos31.mirakc.CANCEL_GR_SCAN"
        const val ACTION_START_SERVER = "dev.khronos31.mirakc.START_SERVER"
        const val ACTION_STOP_SERVER = "dev.khronos31.mirakc.STOP_SERVER"
        private const val TERRESTRIAL_SETTINGS = "terrestrial-channel-settings"
        private const val RUNTIME_SETTINGS = "mirakc-runtime-settings"
        private const val REQUESTED_PUBLIC_RUNNING_KEY = "requested_public_running"
        private const val USB_PERMISSION_ACTION = "dev.khronos31.mirakc.USB_PERMISSION"
        private const val USB_PERMISSION_URI_SCHEME = "mirakc-usb"
        private const val USB_PERMISSION_URI_AUTHORITY = "permission"
        private const val USB_PERMISSION_SCAN_ID = "dev.khronos31.mirakc.USB_PERMISSION_SCAN_ID"
        private const val USB_PERMISSION_OWNER_TOKEN = "dev.khronos31.mirakc.USB_PERMISSION_OWNER_TOKEN"
        private const val NOTIFICATION_CHANNEL = "mirakc-service"
        private const val NOTIFICATION_ID = 40772
        private const val UI_REFRESH_INTERVAL_MS = 500L
        private const val MAX_SCAN_STATUS_BYTES = 4_096L
        private const val SUPERVISOR_QUIESCENCE_TIMEOUT_MS = 30_000L
        private const val EPG_SETTINGS = "epg-update-settings"
        private const val TAG = "MirakcService"
        private val updateNoticeSequence = java.util.concurrent.atomic.AtomicLong(0L)
        private val PX4_PRODUCT_IDS = Px4DeviceModel.entries.flatMap { it.productIds }.toSet()
        private val PROCESS_RUNTIME_OWNER = MirakcProcessRuntimeOwner()
        private val PROCESS_CLEANUP_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mirakc-process-owner-cleanup").apply { isDaemon = true }
        }

        @Volatile
        var statusText: String = "Starting mirakc service..."

        @Volatile
        internal var grScanStatusProvider: (() -> GrScanStatus?)? = null

        @Volatile
        internal var statusSnapshotProvider: (() -> String)? = null

    }
}
