package dev.khronos31.mirakc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import dev.khronos31.updater.GitHubReleaseUpdater
import java.text.NumberFormat

class MirakcTvActivity : Activity() {
    private val updater by lazy { GitHubReleaseUpdater(this, "mirakc", "dev.khronos31.mirakc") }
    private val handler = Handler(Looper.getMainLooper())
    private val channels by lazy {
        TerrestrialChannelSettingsStore(
            AndroidStringSettings(getSharedPreferences("terrestrial-channel-settings", MODE_PRIVATE))
        )
    }
    private val epgInterval by lazy {
        EpgUpdateIntervalStore(AndroidStringSettings(getSharedPreferences(EPG_SETTINGS, MODE_PRIVATE)))
    }

    private lateinit var navInfo: FrameLayout
    private lateinit var navSettings: FrameLayout
    private lateinit var navAbout: FrameLayout
    private lateinit var navInfoIndicator: View
    private lateinit var navSettingsIndicator: View
    private lateinit var navAboutIndicator: View
    private lateinit var contentScroll: ScrollView

    // Cards
    private lateinit var cardInfo: LinearLayout
    private lateinit var serviceStatus: TextView
    private lateinit var channelStatus: TextView

    private lateinit var cardEpgInterval: LinearLayout
    private lateinit var intervalSummary: TextView

    private lateinit var cardChannels: LinearLayout
    private lateinit var channelsSummary: TextView

    private lateinit var cardScan: LinearLayout
    private lateinit var scanSummary: TextView
    private lateinit var scanProgressBar: ProgressBar

    private lateinit var cardUsb: LinearLayout
    private lateinit var cardRestart: LinearLayout
    private lateinit var restartSummary: TextView
    private lateinit var cardStop: LinearLayout
    private lateinit var cardUpdate: LinearLayout
    private lateinit var cardAboutLicenses: LinearLayout
    private lateinit var cardAboutGithub: LinearLayout
    private lateinit var aboutAppVersion: TextView
    private lateinit var aboutBuildNumber: TextView
    private lateinit var aboutSourceCommit: TextView
    private lateinit var aboutMirakcVersion: TextView
    private lateinit var aboutDriverVersions: TextView
    private lateinit var aboutGithubUrl: TextView

    private val sourceMetadata by lazy { ApkSourceMetadata.readOrNull(assets) }

    private var updateCheckInProgress = false

    private val refresh = object : Runnable {
        override fun run() {
            try {
                refreshUi()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to refresh TV status cards", error)
            } finally {
                if (!isFinishing && !isDestroyed) handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)
        initViews()
        startMirakcService()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val focused = currentFocus
        if (focused != null && isRightSideCard(focused)) {
            when (focused) {
                cardInfo -> navInfo.requestFocus()
                cardAboutLicenses, cardAboutGithub -> navAbout.requestFocus()
                else -> navSettings.requestFocus()
            }
        } else {
            super.onBackPressed()
        }
    }

    private fun isRightSideCard(view: View): Boolean {
        return view == cardInfo || view == cardEpgInterval || view == cardChannels ||
            view == cardScan || view == cardUsb || view == cardRestart ||
            view == cardStop || view == cardUpdate || view == cardAboutLicenses || view == cardAboutGithub
    }

    private fun initViews() {
        navInfo = findViewById(R.id.nav_info)
        navSettings = findViewById(R.id.nav_settings)
        navAbout = findViewById(R.id.nav_about)
        navInfoIndicator = findViewById(R.id.nav_info_indicator)
        navSettingsIndicator = findViewById(R.id.nav_settings_indicator)
        navAboutIndicator = findViewById(R.id.nav_about_indicator)
        contentScroll = findViewById(R.id.content_scroll)

        cardInfo = findViewById(R.id.card_info)
        serviceStatus = findViewById(R.id.service_status)
        channelStatus = findViewById(R.id.channel_status)

        cardEpgInterval = findViewById(R.id.card_epg_interval)
        intervalSummary = findViewById(R.id.interval_summary)

        cardChannels = findViewById(R.id.card_channels)
        channelsSummary = findViewById(R.id.channels_summary)

        cardScan = findViewById(R.id.card_scan)
        scanSummary = findViewById(R.id.scan_summary)
        scanProgressBar = findViewById(R.id.scan_progress_bar)

        cardUsb = findViewById(R.id.card_usb)
        cardRestart = findViewById(R.id.card_restart)
        restartSummary = findViewById(R.id.restart_summary)
        cardStop = findViewById(R.id.card_stop)
        cardUpdate = findViewById(R.id.card_update)
        cardAboutLicenses = findViewById(R.id.card_about_licenses)
        cardAboutGithub = findViewById(R.id.card_about_github)
        aboutAppVersion = findViewById(R.id.about_app_version)
        aboutBuildNumber = findViewById(R.id.about_build_number)
        aboutSourceCommit = findViewById(R.id.about_source_commit)
        aboutMirakcVersion = findViewById(R.id.about_mirakc_version)
        aboutDriverVersions = findViewById(R.id.about_driver_versions)
        aboutGithubUrl = findViewById(R.id.about_github_url)

        // Navigation Rail item clicks & key handling
        navInfo.setOnClickListener { jumpToInfo() }
        navSettings.setOnClickListener { jumpToSettings() }
        navAbout.setOnClickListener { jumpToAbout() }

        navInfo.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        navSettings.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        jumpToInfo()
                        true
                    }
                    else -> false
                }
            } else false
        }

        navSettings.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        navInfo.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        navAbout.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        jumpToSettings()
                        true
                    }
                    else -> false
                }
            } else false
        }

        navAbout.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        navSettings.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        jumpToAbout()
                        true
                    }
                    else -> false
                }
            } else false
        }

        // Card Click Listeners
        cardEpgInterval.setOnClickListener { editEpgInterval() }
        cardChannels.setOnClickListener { editChannels() }
        cardScan.setOnClickListener { scanOrCancel() }
        cardUsb.setOnClickListener { sendServiceAction(MirakcService.ACTION_REQUEST_USB) }
        cardRestart.setOnClickListener { restartMirakc() }
        cardStop.setOnClickListener { stopService(Intent(this, MirakcService::class.java)) }
        cardUpdate.setOnClickListener { triggerUpdateCheck() }
        cardAboutLicenses.setOnClickListener { openLicenses() }
        cardAboutGithub.setOnClickListener { openSourceRepository() }

        // Card Left Key -> Left Rail Focus
        cardInfo.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                navInfo.requestFocus()
                true
            } else false
        }

        listOf(cardEpgInterval, cardChannels, cardScan, cardUsb, cardRestart, cardStop, cardUpdate).forEach { card ->
            card.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    navSettings.requestFocus()
                    true
                } else false
            }
        }
        listOf(cardAboutLicenses, cardAboutGithub).forEach { card ->
            card.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    navAbout.requestFocus()
                    true
                } else false
            }
        }

        // Rail Focus Change
        navInfo.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setRailActive(RailSection.INFO)
        }
        navSettings.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setRailActive(RailSection.SETTINGS)
        }
        navAbout.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setRailActive(RailSection.ABOUT)
        }

        // Update Rail Indicator on Focus Change
        cardInfo.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setRailActive(RailSection.INFO)
        }
        val settingsFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setRailActive(RailSection.SETTINGS)
        }
        cardEpgInterval.onFocusChangeListener = settingsFocusListener
        cardChannels.onFocusChangeListener = settingsFocusListener
        cardScan.onFocusChangeListener = settingsFocusListener
        cardUsb.onFocusChangeListener = settingsFocusListener
        cardRestart.onFocusChangeListener = settingsFocusListener
        cardStop.onFocusChangeListener = settingsFocusListener
        cardUpdate.onFocusChangeListener = settingsFocusListener
        val aboutFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) setRailActive(RailSection.ABOUT)
        }
        cardAboutLicenses.onFocusChangeListener = aboutFocusListener
        cardAboutGithub.onFocusChangeListener = aboutFocusListener

        refreshUi()
        setRailActive(RailSection.INFO)
        navInfo.requestFocus()
    }

    private fun setRailActive(section: RailSection) {
        navInfoIndicator.visibility = if (section == RailSection.INFO) View.VISIBLE else View.INVISIBLE
        navSettingsIndicator.visibility = if (section == RailSection.SETTINGS) View.VISIBLE else View.INVISIBLE
        navAboutIndicator.visibility = if (section == RailSection.ABOUT) View.VISIBLE else View.INVISIBLE
        navInfo.isActivated = section == RailSection.INFO
        navSettings.isActivated = section == RailSection.SETTINGS
        navAbout.isActivated = section == RailSection.ABOUT
    }

    private fun jumpToInfo() {
        setRailActive(RailSection.INFO)
        contentScroll.smoothScrollTo(0, 0)
        cardInfo.requestFocus()
    }

    private fun jumpToSettings() {
        setRailActive(RailSection.SETTINGS)
        val target = findViewById<View>(R.id.section_settings_title)
        val rect = Rect()
        target.getDrawingRect(rect)
        contentScroll.offsetDescendantRectToMyCoords(target, rect)
        contentScroll.smoothScrollTo(0, rect.top)
        cardEpgInterval.requestFocus()
    }

    private fun jumpToAbout() {
        setRailActive(RailSection.ABOUT)
        val target = findViewById<View>(R.id.section_about_title)
        val rect = Rect()
        target.getDrawingRect(rect)
        contentScroll.offsetDescendantRectToMyCoords(target, rect)
        contentScroll.smoothScrollTo(0, rect.top)
        cardAboutLicenses.requestFocus()
    }

    private fun refreshUi() {
        if (!::serviceStatus.isInitialized) return
        serviceStatus.text = MirakcService.statusSnapshotProvider?.invoke() ?: MirakcService.statusText
        val snapshot = channels.snapshot()
        val channelsText = TerrestrialChannelSettings.inputText(snapshot.pending?.channels ?: snapshot.active.channels)
        channelStatus.text = getString(R.string.terrestrial_channel_summary, channelsText)

        // Card 1: EPG Interval
        intervalSummary.text = getString(
            R.string.setting_epg_minutes_format,
            NumberFormat.getIntegerInstance().format(epgInterval.minutes())
        )

        // Card 2: Channels
        channelsSummary.text = channelsText

        // Card 3: Scan Status & Result
        val scan = MirakcService.grScanStatusProvider?.invoke()
        if (scan?.isApplicable == true && channels.applyScanResult(scan.scanId, scan.foundChannels)) {
            Toast.makeText(this, getString(R.string.toast_scan_completed), Toast.LENGTH_LONG).show()
        }

        val scanText = scan?.summary() ?: getString(R.string.setting_scan_desc)
        scanSummary.text = scanText

        if (scan != null && (scan.isRunning || scan.completed > 0)) {
            scanProgressBar.visibility = View.VISIBLE
            scanProgressBar.isIndeterminate = scan.isIndeterminate
            scanProgressBar.progress = scan.completed
        } else {
            scanProgressBar.isIndeterminate = false
            scanProgressBar.visibility = View.GONE
        }

        // Card 5: Restart
        restartSummary.text = getString(R.string.setting_restart_desc)

        // Accessibility content descriptions
        cardInfo.contentDescription = "${serviceStatus.text}. ${channelStatus.text}"
        cardEpgInterval.contentDescription = getString(
            R.string.setting_card_content_description,
            getString(R.string.setting_epg_interval_title),
            intervalSummary.text
        )
        cardChannels.contentDescription = getString(
            R.string.setting_card_content_description,
            getString(R.string.setting_channel_title),
            channelsSummary.text
        )

        populateAbout(sourceMetadata)
        cardAboutLicenses.contentDescription = getString(
            R.string.setting_card_content_description,
            getString(R.string.about_licenses_title),
            getString(R.string.about_licenses_desc)
        )
        cardAboutGithub.contentDescription = getString(
            R.string.setting_card_content_description,
            getString(R.string.about_github_title),
            aboutGithubUrl.text
        )
        cardScan.contentDescription = getString(
            R.string.setting_card_content_description,
            getString(R.string.setting_scan_title),
            scanSummary.text
        )
    }

    private fun populateAbout(metadata: ApkSourceMetadata?) {
        val appComponent = metadata?.component("dtv-android")
        val unavailable = getString(R.string.about_metadata_unavailable)
        aboutAppVersion.text = BuildConfig.VERSION_NAME.takeIf { it.isNotBlank() } ?: unavailable
        aboutBuildNumber.text = BuildConfig.VERSION_CODE.toString()
        aboutSourceCommit.text = appComponent?.commit ?: unavailable
        aboutMirakcVersion.text = listOfNotNull(
            metadata?.component("mirakc"),
            metadata?.component("mirakc-arib")
        ).joinToString("\n") { "${it.name} ${it.version}" }.ifBlank { unavailable }
        aboutDriverVersions.text = listOfNotNull(
            metadata?.component("siano-userland"),
            metadata?.component("px4-userland"),
            metadata?.component("px4_drv")
        ).joinToString("\n") { "${it.name} ${it.version}" }.ifBlank { unavailable }
        aboutGithubUrl.text = appComponent?.url?.takeIf(::isGitHubHttpsUrl) ?: unavailable
    }

    private fun openLicenses() {
        startActivity(Intent(this, LicenseActivity::class.java))
    }

    private fun openSourceRepository() {
        val url = sourceMetadata?.component("dtv-android")?.url?.takeIf(::isGitHubHttpsUrl)
        if (url == null) {
            Toast.makeText(this, R.string.about_metadata_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_source_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun isGitHubHttpsUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "https" && uri.host == "github.com" && uri.pathSegments.isNotEmpty()
    }

    private fun editEpgInterval() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = getString(R.string.hint_epg_interval)
            setText(epgInterval.minutes().toString())
            selectAll()
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this, R.style.TvDialogTheme)
            .setTitle(R.string.dialog_epg_interval_title)
            .setMessage(R.string.dialog_epg_interval_message)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val minutes = epgInterval.save(input.text.toString())
                    Toast.makeText(
                        this,
                        getString(
                            R.string.toast_epg_saved,
                            NumberFormat.getIntegerInstance().format(minutes)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshUi()
                    dialog.dismiss()
                    cardEpgInterval.requestFocus()
                } catch (error: IllegalArgumentException) {
                    input.error = error.message ?: getString(R.string.error_epg_interval)
                    input.requestFocus()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                cardEpgInterval.requestFocus()
            }
            prepareInputDialogFocus(dialog, input)
            configureInputDialog(input, dialog, cardEpgInterval)
        }
        dialog.show()
    }

    private fun editChannels() {
        val input = EditText(this).apply {
            // A numeric keypad includes digits, commas, and minus signs,
            // which are the complete syntax for physical-channel lists.
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            hint = getString(R.string.hint_channels)
            setText(TerrestrialChannelSettings.inputText(channels.inputState().channels))
            selectAll()
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this, R.style.TvDialogTheme)
            .setTitle(R.string.dialog_channels_title)
            .setMessage(R.string.dialog_channels_message)
            .setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    channels.savePending(input.text.toString())
                    Toast.makeText(
                        this,
                        getString(R.string.toast_channels_saved),
                        Toast.LENGTH_LONG
                    ).show()
                    refreshUi()
                    dialog.dismiss()
                    cardChannels.requestFocus()
                } catch (error: IllegalArgumentException) {
                    input.error = error.message ?: getString(R.string.error_channels)
                    input.requestFocus()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                cardChannels.requestFocus()
            }
            prepareInputDialogFocus(dialog, input)
            configureInputDialog(input, dialog, cardChannels)
        }
        dialog.show()
    }

    private fun configureInputDialog(input: EditText, dialog: AlertDialog, returnFocus: View) {
        dialog.setOnCancelListener { returnFocus.requestFocus() }
        dialog.setOnDismissListener { returnFocus.requestFocus() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enterReleased = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_DONE || enterReleased) {
                hideInputMethod(input)
                // IME Done closes editing only. Put focus on Cancel so a
                // directional press cannot accidentally activate Save.
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
                true
            } else false
        }

        val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideInputMethod(input)
                cancelButton.requestFocus()
                true
            } else false
        }
        // The Leanback dialog theme lays out Save before Cancel. Keep the
        // expected horizontal D-pad navigation deterministic across devices.
        saveButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cancelButton.requestFocus()
                true
            } else false
        }
        cancelButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                saveButton.requestFocus()
                true
            } else false
        }
    }

    private fun hideInputMethod(input: EditText) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun prepareInputDialogFocus(dialog: AlertDialog, input: EditText) {
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        input.post {
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            // Some TV IMEs reset the cursor while opening. Select after they
            // attach so typing replaces the previous value reliably.
            input.postDelayed({
                if (input.hasFocus()) input.selectAll()
            }, INPUT_SELECTION_DELAY_MS)
        }
    }

    private fun scanOrCancel() {
        val scan = MirakcService.grScanStatusProvider?.invoke()
        val action = if (scan?.isRunning == true) {
            MirakcService.ACTION_CANCEL_GR_SCAN
        } else {
            MirakcService.ACTION_SCAN_GR
        }
        sendServiceAction(action)
    }

    private fun restartMirakc() {
        sendServiceAction(MirakcService.ACTION_APPLY_TERRESTRIAL)
        Toast.makeText(this, getString(R.string.toast_restarting), Toast.LENGTH_LONG).show()
    }

    private fun startMirakcService() {
        val intent = Intent(this, MirakcService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, MirakcService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun triggerUpdateCheck() {
        if (updateCheckInProgress) return
        updateCheckInProgress = true
        val restoreFocus = cardUpdate.hasFocus()
        cardUpdate.isClickable = false
        cardUpdate.alpha = 0.5f
        updater.check { result ->
            runOnUiThread {
                updateCheckInProgress = false
                cardUpdate.isClickable = true
                cardUpdate.alpha = 1.0f
                val restore = {
                    if (restoreFocus && !isFinishing) cardUpdate.requestFocus()
                }
                when (result) {
                    is GitHubReleaseUpdater.CheckResult.UpToDate -> {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_already_up_to_date, result.installedVersion),
                            Toast.LENGTH_LONG
                        ).show()
                        restore()
                    }
                    is GitHubReleaseUpdater.CheckResult.Failure -> showUpdateError(result.message, restore)
                    is GitHubReleaseUpdater.CheckResult.UpdateAvailable -> {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_update_available, result.update.versionText),
                            Toast.LENGTH_LONG
                        ).show()
                        restore()
                    }
                }
            }
        }
    }

    private fun showUpdateError(message: String, onDismiss: () -> Unit) {
        val dialog = AlertDialog.Builder(this, R.style.TvDialogTheme)
            .setTitle(R.string.dialog_update_failed_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }

    private companion object {
        const val EPG_SETTINGS = "epg-update-settings"
        const val REFRESH_INTERVAL_MS = 1_000L
        const val INPUT_SELECTION_DELAY_MS = 250L
        private const val TAG = "MirakcTvActivity"
    }

    private enum class RailSection { INFO, SETTINGS, ABOUT }
}
