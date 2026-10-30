package dev.khronos31.mirakc

import java.io.File

internal object UserlandRuntimeLayout {
    const val PIX_ROOT = "x1"
    const val ASICEN_ROOT = "a1"
    const val CONTROL_ENDPOINT = "control.sock"

    fun rootDirectory(filesDir: File, product: UserlandProduct): File =
        File(filesDir, if (product == UserlandProduct.PIX) PIX_ROOT else ASICEN_ROOT)

    fun runtimeDirectoryForEnclosure(
        runtimeRoot: File,
        @Suppress("UNUSED_PARAMETER") instanceToken: String
    ): File = runtimeRoot

    fun controlEndpoint(runtimeDirectory: File, product: UserlandProduct, instanceToken: String): File =
        File(runtimeDirectory, "${product.statusProduct}/v1/$instanceToken/$CONTROL_ENDPOINT")
}
