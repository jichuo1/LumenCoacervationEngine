package com.lumen.coacervation.engine.host

/** Physical corners, in dp: top-left, top-right, bottom-right, bottom-left. */
public data class LumenSurfaceCorners(
    val topLeft: Float = 24f, val topRight: Float = 24f,
    val bottomRight: Float = 24f, val bottomLeft: Float = 24f
) {
    init { listOf(topLeft,topRight,bottomRight,bottomLeft).forEach { bounded(it,0f,128f) } }
    public fun mirrored(): LumenSurfaceCorners = LumenSurfaceCorners(topRight,topLeft,bottomLeft,bottomRight)
}

public enum class LumenMaterialIntent { UNCHANGED, READING, CLEAR, OPAQUE_ACCESSIBLE, DECORATIVE }
public enum class LumenDetailMode { LOW, BALANCED, HIGH }
public enum class LumenSamplingRangeMode { SAFE_INTERIOR, PADDED_EXPERIMENTAL }

public data class LumenSurfaceGeometryOptions(
    val cornersEnabled: Boolean = false,
    val corners: LumenSurfaceCorners = LumenSurfaceCorners(),
    val mirrorCornersInRtl: Boolean = false,
    val fusionEnabled: Boolean = false,
    val fusionRadiusDp: Float = 16f,
    val antiAliasWidthDp: Float = 0.75f,
    val shadowEnabled: Boolean = false,
    val shadowRadiusDp: Float = 8f,
    val shadowOpacity: Float = 0.20f
) {
    init { bounded(fusionRadiusDp,0f,64f);bounded(antiAliasWidthDp,0.25f,2f);bounded(shadowRadiusDp,0f,24f);bounded(shadowOpacity,0f,0.8f) }
}

/** Clear, weak and strong fixed outputs mixed by smooth masks; not a variable-sigma Gaussian. */
public data class LumenProgressiveBlurOptions(
    val enabled: Boolean = false,
    val weakRadiusDp: Float = 4f,
    val strongRadiusDp: Float = 16f,
    val weakStart: Float = 0f,
    val weakEnd: Float = 0.65f,
    val strongStart: Float = 0.40f,
    val strongEnd: Float = 1f,
    val direction: LumenSurfaceFadeDirection = LumenSurfaceFadeDirection.TOP_TO_BOTTOM,
    val strength: Float = 1f,
    val maxBandHeightDp: Float = 192f
) {
    init {
        bounded(weakRadiusDp,0f,48f);bounded(strongRadiusDp,weakRadiusDp,48f)
        bounded(weakStart,0f,1f);bounded(weakEnd,weakStart,1f)
        bounded(strongStart,0f,1f);bounded(strongEnd,strongStart,1f)
        bounded(strength,0f,1f);bounded(maxBandHeightDp,24f,256f)
    }
}

public data class LumenLocalPressOptions(
    val enabled: Boolean = false,
    val displacementDp: Float = 3f,
    val radiusFraction: Float = 0.30f,
    val highlightStrength: Float = 0.6f,
    val cancelOutside: Boolean = true,
    val releaseDurationMs: Long = 180L,
    val rippleEnabled: Boolean = false,
    val rippleAmplitudeDp: Float = 1.2f,
    val rippleSpeedDpPerSecond: Float = 160f,
    val rippleWidthDp: Float = 12f,
    val rippleLifetimeMs: Long = 600L,
    val maxRipples: Int = 2
) {
    init {
        bounded(displacementDp,-8f,8f);bounded(radiusFraction,0.05f,0.75f);bounded(highlightStrength,0f,3f)
        require(releaseDurationMs in 50L..1_000L)
        bounded(rippleAmplitudeDp,0f,4f);bounded(rippleSpeedDpPerSecond,20f,400f);bounded(rippleWidthDp,2f,48f)
        require(rippleLifetimeMs in 100L..2_000L);require(maxRipples in 1..4)
    }
}

/** Stable light and transform-driven inputs; no sensor is started by the engine. */
public data class LumenSurfaceLightOptions(
    val enabled: Boolean = false,
    val angleDegrees: Float = 145f,
    val altitude: Float = 0.65f,
    val intensity: Float = 1f,
    val specularStrength: Float = 0.8f,
    val specularPower: Float = 18f,
    val edgeWidthDp: Float = 2f,
    val transformNormals: Boolean = true,
    val gestureInfluence: Float = 0.25f,
    val smoothingTimeMs: Long = 120L
) {
    init {
        bounded(angleDegrees,0f,360f);bounded(altitude,0.05f,1f);bounded(intensity,0f,3f)
        bounded(specularStrength,0f,3f);bounded(specularPower,2f,64f);bounded(edgeWidthDp,0.25f,12f)
        bounded(gestureInfluence,0f,1f);require(smoothingTimeMs in 0L..1_000L)
    }
}

public data class LumenMaterialRecipeOptions(
    val intent: LumenMaterialIntent = LumenMaterialIntent.UNCHANGED,
    val normalizeBySize: Boolean = false,
    val maxEdgeFraction: Float = 0.15f,
    val maxRefractionFraction: Float = 0.10f,
    val contrastFloor: Float = 0.72f,
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val chromaticStrength: Float = 0.25f,
    val saturation: Float = 1f,
    val useIntentDefaults: Boolean = true
) {
    init {
        bounded(maxEdgeFraction,0.02f,0.4f);bounded(maxRefractionFraction,0f,0.25f);bounded(contrastFloor,0.5f,1f)
        bounded(chromaticStrength,0f,2f);bounded(saturation,0f,2f)
    }
}

/** Separate from the existing thermal/throughput policy. It never changes a persisted SkinId. */
public data class LumenSurfaceQualityOptions(
    val enabled: Boolean = false,
    val mode: LumenDetailMode = LumenDetailMode.BALANCED,
    val adaptive: Boolean = false,
    val lowerThreshold: Float = 0.35f,
    val upperThreshold: Float = 0.75f,
    val hysteresis: Float = 0.08f,
    val minimumDwellMs: Long = 1_500L,
    val maxExecutionPixels: Int = 1_048_576,
    val maxBitmapPixels: Int = 24_000,
    val bitmapIntervalMs: Long = 28L
) {
    init {
        bounded(lowerThreshold,0.05f,0.9f);bounded(upperThreshold,lowerThreshold,0.95f);bounded(hysteresis,0f,0.2f)
        require(minimumDwellMs in 250L..10_000L);require(maxExecutionPixels in 16_384..4_194_304)
        require(maxBitmapPixels in 1_024..96_000);require(bitmapIntervalMs in 0L..1_000L)
    }
}

public data class LumenSurfaceDebugOptions(
    val countersEnabled: Boolean = true,
    val timingEnabled: Boolean = false,
    val drawSamplingBounds: Boolean = false,
    val boundsLineWidthDp: Float = 1f,
    val boundsOpacity: Float = 0.65f,
    val samplingRange: LumenSamplingRangeMode = LumenSamplingRangeMode.SAFE_INTERIOR
) {
    init { bounded(boundsLineWidthDp,0.5f,4f);bounded(boundsOpacity,0.1f,1f) }
}

/** Added separately so all 1.1 constructors/copy methods remain binary compatible. */
public data class LumenSurfaceEnhancements(
    val geometry: LumenSurfaceGeometryOptions = LumenSurfaceGeometryOptions(),
    val progressiveBlur: LumenProgressiveBlurOptions = LumenProgressiveBlurOptions(),
    val press: LumenLocalPressOptions = LumenLocalPressOptions(),
    val light: LumenSurfaceLightOptions = LumenSurfaceLightOptions(),
    val material: LumenMaterialRecipeOptions = LumenMaterialRecipeOptions(),
    val quality: LumenSurfaceQualityOptions = LumenSurfaceQualityOptions(),
    val debug: LumenSurfaceDebugOptions = LumenSurfaceDebugOptions()
) {
    public companion object { public val DEFAULT: LumenSurfaceEnhancements = LumenSurfaceEnhancements() }
}

private fun bounded(value: Float, min: Float, max: Float) { require(value.isFinite() && value in min..max) }
