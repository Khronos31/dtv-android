package dev.khronos31.mirakc

/** Classifies USB broadcasts so unrelated accessories and CCID readers do not restart mirakc. */
internal object MirakcUsbEventPolicy {
    fun affectsTunerConfiguration(vendorId: Int, productId: Int): Boolean =
        isSianoTuner(vendorId, productId) || isPx4Tuner(vendorId, productId)

    fun isPx4Tuner(vendorId: Int, productId: Int): Boolean =
        vendorId == PX4_VENDOR_ID && Px4DeviceSelector.modelForProductId(productId) != null

    fun requiresMirakcReconfigureOnLifecycle(
        vendorId: Int,
        productId: Int,
        isCcidReader: Boolean,
        isAttached: Boolean,
        permissionGranted: Boolean
    ): Boolean =
        (affectsTunerConfiguration(vendorId, productId) || isCcidReader) &&
            (!isAttached || permissionGranted)

    fun requiresMirakcReconfigureOnPermissionGrant(
        vendorId: Int,
        productId: Int,
        isCcidReader: Boolean
    ): Boolean = affectsTunerConfiguration(vendorId, productId) || isCcidReader

    private fun isSianoTuner(vendorId: Int, productId: Int): Boolean =
        vendorId == SIANO_VENDOR_ID && productId == SIANO_PRODUCT_ID ||
            vendorId == SIANO_COMPAT_VENDOR_ID && productId in SIANO_COMPAT_PRODUCT_IDS

    private const val PX4_VENDOR_ID = 0x0511
    private const val SIANO_VENDOR_ID = 0x3275
    private const val SIANO_PRODUCT_ID = 0x0080
    private const val SIANO_COMPAT_VENDOR_ID = 0x187f
    private val SIANO_COMPAT_PRODUCT_IDS = setOf(0x0600, 0x0302)
}
