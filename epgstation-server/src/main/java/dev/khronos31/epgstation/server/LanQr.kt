package dev.khronos31.epgstation.server

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface

internal object LanQr {
    fun listenUrls(port: Int): List<String> {
        val hosts = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf("http://127.0.0.1:$port/")
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            for (address in nic.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    hosts += "http://${address.hostAddress}:$port/"
                }
            }
        }
        return hosts.ifEmpty { listOf("http://127.0.0.1:$port/") }
    }

    fun bitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }
}
