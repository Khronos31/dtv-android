package dev.khronos31.mirakc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = MirakcService.statusText
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildStatusScreen()
        startMirakcService()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun buildStatusScreen() {
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
            setPadding(0, 0, 0, 18)
        }
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.LTGRAY)
            isFocusable = false
        }
        val request = Button(this).apply {
            text = "Request USB permission"
            setOnClickListener { sendServiceAction(MirakcService.ACTION_REQUEST_USB) }
        }
        val stop = Button(this).apply {
            text = "Stop mirakc service"
            setOnClickListener { stopService(Intent(this@MainActivity, MirakcService::class.java)) }
        }
        val start = Button(this).apply {
            text = "Start mirakc service"
            setOnClickListener { startMirakcService() }
        }
        root.addView(title)
        root.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(request, LinearLayout.LayoutParams(-1, 56))
        root.addView(stop, LinearLayout.LayoutParams(-1, 56))
        root.addView(start, LinearLayout.LayoutParams(-1, 56))
        setContentView(root)
        request.requestFocus()
    }

    private fun startMirakcService() {
        val intent = Intent(this, MirakcService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, MirakcService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }
}
