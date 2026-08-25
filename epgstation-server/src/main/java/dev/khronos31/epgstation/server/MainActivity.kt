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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URI

class MainActivity : Activity() {
    private lateinit var preferences: SharedPreferences
    private lateinit var urlInput: EditText

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

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 32, 48, 32)
            setBackgroundColor(Color.rgb(22, 27, 31))
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(Color.WHITE)
        }
        val explanation = TextView(this).apply {
            text = "Bundled EPGStation v2.10.0\nListening on port 8888\n\nMirakurun / mirakc base URL"
            textSize = 17f
            setTextColor(Color.LTGRAY)
            setPadding(0, 18, 0, 12)
        }
        urlInput = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            textSize = 18f
            setText(preferences.getString(KEY_MIRAKURUN_URL, DEFAULT_MIRAKURUN_URL))
            hint = DEFAULT_MIRAKURUN_URL
            contentDescription = "Mirakurun or mirakc base URL"
            setPadding(24, 20, 24, 20)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val save = Button(this).apply {
            text = "Save base URL"
            setOnClickListener { saveUrl() }
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(explanation, LinearLayout.LayoutParams(-1, -2))
        root.addView(
            urlInput,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = 8
                bottomMargin = 16
            }
        )
        root.addView(save, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        save.requestFocus()
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
        const val DEFAULT_MIRAKURUN_URL = "http://127.0.0.1:40772/"
    }
}
