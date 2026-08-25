package dev.khronos31.epgstation.server

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URI

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var urlInput: EditText
    private lateinit var storageList: LinearLayout
    private lateinit var storageStatus: TextView
    private lateinit var qrView: ImageView
    private lateinit var listenUrl: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        buildScreen()
        startServerService()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStorageList()
        refreshQr()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setBackgroundColor(Color.rgb(22, 27, 31))
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(Color.WHITE)
        }
        val explanation = TextView(this).apply {
            text = "Open this URL on a phone or PC. Guide, reserves, and settings stay in the browser."
            textSize = 17f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(8))
        }
        qrView = ImageView(this).apply {
            adjustViewBounds = true
            isFocusable = false
            contentDescription = "EPGStation listen URL QR code"
        }
        listenUrl = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(16))
        }
        val mirakurunHeading = TextView(this).apply {
            text = "Mirakurun / mirakc base URL"
            textSize = 17f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        }
        urlInput = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            textSize = 18f
            setText(preferences.getString(KEY_MIRAKURUN_URL, DEFAULT_MIRAKURUN_URL))
            hint = DEFAULT_MIRAKURUN_URL
            contentDescription = "Mirakurun or mirakc base URL"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val save = tvButton("Save base URL") { saveUrl() }
        val storageHeading = TextView(this).apply {
            text = "Recording storage"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(18), 0, dp(4))
        }
        storageStatus = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(8))
        }
        storageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val qrSize = dp(220)
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(explanation, LinearLayout.LayoutParams(-1, -2))
        root.addView(qrView, LinearLayout.LayoutParams(qrSize, qrSize).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(listenUrl, LinearLayout.LayoutParams(-1, -2))
        root.addView(mirakurunHeading, LinearLayout.LayoutParams(-1, -2))
        root.addView(
            urlInput,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
        )
        root.addView(save, LinearLayout.LayoutParams(-1, -2))
        root.addView(storageHeading, LinearLayout.LayoutParams(-1, -2))
        root.addView(storageStatus, LinearLayout.LayoutParams(-1, -2))
        root.addView(storageList, LinearLayout.LayoutParams(-1, -2))
        val scroller = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(22, 27, 31))
            addView(root, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(scroller)
        refreshStorageList()
        refreshQr()
        save.requestFocus()
    }

    private fun refreshQr() {
        if (!::qrView.isInitialized) return
        val urls = LanQr.listenUrls(EpgStationService.PORT)
        listenUrl.text = urls.joinToString("\n")
        qrView.setImageBitmap(LanQr.bitmap(urls.first(), dp(220)))
    }

    private fun tvButton(label: String, click: View.OnClickListener): Button {
        return Button(this).apply {
            text = label
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = false
            minHeight = dp(48)
            setOnClickListener(click)
        }
    }

    private fun refreshStorageList() {
        if (!::storageList.isInitialized) return
        storageList.removeAllViews()
        val selected = RecordingStorage.selected(this)
        storageStatus.text = "Now: ${selected.recordedDir.absolutePath}"
        for (volume in RecordingStorage.list(this)) {
            val mark = if (volume.id == selected.id) "✓ " else ""
            val enabled = volume.available || volume.id == RecordingStorage.INTERNAL_ID
            val button = tvButton("$mark${volume.title}\n${volume.detail}") {
                if (!volume.available) return@tvButton
                RecordingStorage.save(this, volume.id)
                refreshStorageList()
                startServerService()
            }
            button.isAllCaps = false
            button.isEnabled = enabled
            storageList.addView(
                button,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            )
        }
    }

    private fun saveUrl() {
        val value = urlInput.text.toString().trim()
        val parsed = runCatching { URI(value) }.getOrNull()
        if (parsed == null || parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            urlInput.error = "Enter an absolute http:// or https:// URL"
            return
        }
        val normalized = if (value.endsWith('/')) value else "$value/"
        preferences.edit().putString(KEY_MIRAKURUN_URL, normalized).apply()
        urlInput.setText(normalized)
        startServerService()
    }

    private fun startServerService() {
        val intent = Intent(this, EpgStationService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    companion object {
        const val PREFERENCES = "epgstation-server"
        const val KEY_MIRAKURUN_URL = "mirakurun_url"
        const val KEY_RECORDED_VOLUME = "recorded_volume"
        const val DEFAULT_MIRAKURUN_URL = "http://127.0.0.1:40772/"
    }
}
