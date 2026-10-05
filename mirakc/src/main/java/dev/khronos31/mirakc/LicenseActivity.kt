package dev.khronos31.mirakc

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class LicenseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_licenses)

        val content = findViewById<TextView>(R.id.licenses_content_text)
        content.text = renderLicenseInventory(this, assets, ApkSourceMetadata.readOrNull(assets))
        content.requestFocus()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finish()
    }
}
