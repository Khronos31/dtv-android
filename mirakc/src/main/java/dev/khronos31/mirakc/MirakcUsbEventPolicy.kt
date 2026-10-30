package dev.khronos31.mirakc

/** Classifies USB broadcasts so unrelated accessories and CCID readers do not restart mirakc. */
internal object MirakcUsbEventPolicy {
    fun affectsTunerConfiguration(vendorId: Int, productId: Int): Boolean =
        isSianoTuner(vendorId, productId) || isPx4Tuner(vendorId, productId) || isPixTuner(vendorId, productId) || isAsicenRuntimeTuner(vendorId, productId)

    fun isPixTuner(vendorId: Int, productId: Int): Boolean =
        vendorId == PIX_VENDOR_ID && productId in PIX_PRODUCT_IDS

    fun isAsicenTuner(vendorId: Int, productId: Int): Boolean =
        isAsicenRuntimeTuner(vendorId, productId) ||
            vendorId == ASICEN_LOADER_VENDOR_ID && productId in ASICEN_LOADER_PRODUCT_IDS

    fun isAsicenRuntimeTuner(vendorId: Int, productId: Int): Boolean =
        vendorId == ASICEN_RUNTIME_VENDOR_ID && productId in ASICEN_RUNTIME_PRODUCT_IDS

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

    fun requiresAsicenReconfigureOnPermissionGrant(
        vendorId: Int,
        productId: Int,
        stage: AsicenProvisioningStage?
    ): Boolean = isAsicenRuntimeTuner(vendorId, productId) &&
        (stage == null || stage == AsicenProvisioningStage.START_RUNTIME || stage == AsicenProvisioningStage.READY)

    private fun isSianoTuner(vendorId: Int, productId: Int): Boolean =
        vendorId == SIANO_VENDOR_ID && productId == SIANO_PRODUCT_ID ||
            vendorId == SIANO_COMPAT_VENDOR_ID && productId in SIANO_COMPAT_PRODUCT_IDS

    private const val PX4_VENDOR_ID = 0x0511
    private const val PIX_VENDOR_ID = 0x06b8
    private const val ASICEN_RUNTIME_VENDOR_ID = 0x0b06
    private const val ASICEN_LOADER_VENDOR_ID = 0x1738
    private val PIX_PRODUCT_IDS = setOf(0x102b, 0x103b)
    private val ASICEN_RUNTIME_PRODUCT_IDS = setOf(0x0001, 0x0003, 0x0004, 0x0005, 0x0006)
    private val ASICEN_LOADER_PRODUCT_IDS = setOf(0x5211, 0x5216)
    private const val SIANO_VENDOR_ID = 0x3275
    private const val SIANO_PRODUCT_ID = 0x0080
    private const val SIANO_COMPAT_VENDOR_ID = 0x187f
    private val SIANO_COMPAT_PRODUCT_IDS = setOf(0x0600, 0x0302)
}
