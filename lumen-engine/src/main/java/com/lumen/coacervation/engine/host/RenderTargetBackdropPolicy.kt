package com.lumen.coacervation.engine.host

/** Eligibility for the explicit current-render-target backdrop; ordinary sources keep their sampling path. */
internal object RenderTargetBackdropPolicy {
    const val MIN_SDK_FULL = 3_700_002

    /** The caller reads SDK_INT_FULL only after checking SDK_INT >= 36. */
    fun versionSupported(sdk: Int, sdkFull: Int): Boolean = sdk >= 37 && sdkFull >= MIN_SDK_FULL

    /** Cropping the native snapshot at a window edge changes the filter's origin; retain explicit sampling. */
    fun windowContains(x: Int, y: Int, width: Int, height: Int, windowWidth: Int, windowHeight: Int, padding: Int): Boolean =
        width > 0 && height > 0 && padding >= 0 && x.toLong() >= padding && y.toLong() >= padding &&
            x.toLong() + width + padding <= windowWidth && y.toLong() + height + padding <= windowHeight

    /**
     * Native backdrop is limited to an untransformed, fully opaque base frosted View background.
     * The binding owns the sticky [failed] state; this policy never resets it when other gates change.
     */
    fun eligible(
        requested: Boolean,
        sdkSupported: Boolean,
        hardwareCanvas: Boolean,
        viewBackground: Boolean,
        frosted: Boolean,
        refraction: Boolean,
        fade: Boolean,
        enhanced: Boolean,
        preserveColors: Boolean,
        prism: Boolean,
        fullOpacity: Boolean,
        untransformed: Boolean,
        gpuAllowed: Boolean,
        failed: Boolean
    ): Boolean = requested && sdkSupported && hardwareCanvas && viewBackground && frosted &&
        !refraction && !fade && !enhanced && !preserveColors && !prism && fullOpacity &&
        untransformed && gpuAllowed && !failed
}
