package dev.khronos31.mirakc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.Toast
import dev.khronos31.updater.GitHubReleaseUpdater

class MainActivity : Activity() {
    private val updater by lazy { GitHubReleaseUpdater(this, "mirakc", "dev.khronos31.mirakc") }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun tvButton(label: String, click: View.OnClickListener): Button {
        return Button(this).apply {
            text = label
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = false
            minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener(click)
        }
    }

    private fun buildStatusScreen() {
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
            setPadding(0, 0, 0, dp(12))
        }
        status = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.LTGRAY)
            isFocusable = false
        }
        val request = tvButton("Request USB permission") {
            sendServiceAction(MirakcService.ACTION_REQUEST_USB)
        }
        val stop = tvButton("Stop mirakc service") {
            stopService(Intent(this@MainActivity, MirakcService::class.java))
        }
        val start = tvButton("Start mirakc service") {
            startMirakcService()
        }
        val checkUpdate = tvButton("CHECK UPDATE") {
            checkForUpdate(it as Button)
        }
        val scan = tvButton("Scan EPG") {
            sendServiceAction(MirakcService.ACTION_SCAN_EPG)
        }
        val buttonParams = {
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(request, buttonParams())
        root.addView(scan, buttonParams())
        root.addView(stop, buttonParams())
        root.addView(start, buttonParams())
        root.addView(checkUpdate, buttonParams())
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

    private fun checkForUpdate(button: Button) {
        button.isEnabled = false
        updater.check { result ->
            button.isEnabled = true
            when (result) {
                is GitHubReleaseUpdater.CheckResult.UpToDate ->
                    Toast.makeText(this, "Already up to date (${result.installedVersion})", Toast.LENGTH_LONG).show()
                is GitHubReleaseUpdater.CheckResult.Failure ->
                    showUpdateError(result.message)
                is GitHubReleaseUpdater.CheckResult.UpdateAvailable -> {
                    val update = result.update
                    AlertDialog.Builder(this)
                        .setTitle("Update available")
                        .setMessage("mirakc ${update.versionText} is available. Download and install it?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download and install") { _, _ ->
                            button.isEnabled = false
                            updater.downloadAndInstall(update, this) { downloadResult ->
                                button.isEnabled = true
                                when (downloadResult) {
                                    GitHubReleaseUpdater.DownloadResult.InstallerLaunched -> Unit
                                    GitHubReleaseUpdater.DownloadResult.NeedUnknownSourcesPermission ->
                                        AlertDialog.Builder(this)
                                            .setTitle("Permission required")
                                            .setMessage("Allow this app to install updates, then press CHECK UPDATE again.")
                                            .setPositiveButton("Open settings") { _, _ -> updater.openUnknownSourcesSettings(this) }
                                            .setNegativeButton("Cancel", null)
                                            .show()
                                    is GitHubReleaseUpdater.DownloadResult.Failure -> showUpdateError(downloadResult.message)
                                }
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun showUpdateError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Update check failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
