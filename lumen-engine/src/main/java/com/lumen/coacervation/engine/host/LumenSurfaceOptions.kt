package com.lumen.coacervation.engine.host

import com.lumen.coacervation.engine.model.SurfaceRole

/** Local surfaces never read preferences or take ownership of an Activity window. */
public enum class LumenSurfaceMaterial { FROSTED, LIQUID, STATIC }
public enum class LumenSurfaceBackend { AUTO, GPU, SOFTWARE, STATIC }
public enum class LumenSurfaceFadeDirection { TOP_TO_BOTTOM, BOTTOM_TO_TOP }
public enum class LumenSurfaceFailure {
    NONE, NO_SOURCE, DETACHED, HIDDEN, DIFFERENT_WINDOW, SELF_FEEDBACK,
    INVALID_GEOMETRY, GPU_UNAVAILABLE, GPU_FAILED, SOFTWARE_FAILED, BUDGET_EXCEEDED,
    FRAME_PENDING, CALLBACK_FAILED, PAUSED, MEMORY_PRESSURE, CLOSED
}

/** Per-surface sampling. Defaults keep a small, throttled software fallback available. */
public data class LumenSurfaceSampling(
    val enabled: Boolean = true,
    val backend: LumenSurfaceBackend = LumenSurfaceBackend.AUTO,
    val blurEnabled: Boolean = true,
    val blurRadiusDp: Float = 10f,
    val refractionEnabled: Boolean = true,
    val refractionStrength: Float = 1f,
    val minIntervalMs: Long = 28L,
    val softwareScale: Float = 1f / 3f,
    val maxSoftwarePixels: Int = 24_000,
    val softwareFallback: Boolean = true,
    val fadeEnabled: Boolean = false,
    val fadeHold: Float = 0f,
    val fadeEnd: Float = 1f,
    val fadeDirection: LumenSurfaceFadeDirection = LumenSurfaceFadeDirection.TOP_TO_BOTTOM
) {
    init {
        require(blurRadiusDp.isFinite() && blurRadiusDp in 0f..48f)
        require(refractionStrength.isFinite() && refractionStrength in 0f..2f)
        require(minIntervalMs in 0L..1_000L)
        require(softwareScale.isFinite() && softwareScale in 0.05f..1f)
        require(maxSoftwarePixels in 1_024..MAX_SOFTWARE_PIXELS)
        require(fadeHold.isFinite() && fadeEnd.isFinite() && fadeHold in 0f..1f && fadeEnd in fadeHold..1f)
    }

    public companion object { public const val MAX_SOFTWARE_PIXELS: Int = 96_000 }
}

/** Appearance changes are explicit; text, children, clicks and navigation remain caller-owned. */
public data class LumenSurfaceOptions(
    val enabled: Boolean = true,
    val material: LumenSurfaceMaterial = LumenSurfaceMaterial.FROSTED,
    val role: SurfaceRole = SurfaceRole.FLOATING,
    val radiusDp: Float = 24f,
    val opacity: Float = 1f,
    val color: Int? = null,
    val tintEnabled: Boolean = true,
    val tintOpacity: Float = 0.20f,
    val fallbackTintOpacity: Float = 0.80f,
    val edgeEnabled: Boolean = true,
    val edgeWidthDp: Float = 1f,
    val edgeIntensity: Float = 1f,
    val clipBackground: Boolean = true,
    val sampling: LumenSurfaceSampling = LumenSurfaceSampling()
) {
    init {
        require(radiusDp.isFinite() && radiusDp in 0f..128f)
        require(opacity.isFinite() && opacity in 0f..1f)
        require(tintOpacity.isFinite() && tintOpacity in 0f..1f)
        require(fallbackTintOpacity.isFinite() && fallbackTintOpacity in 0f..1f)
        require(edgeWidthDp.isFinite() && edgeWidthDp in 0f..8f)
        require(edgeIntensity.isFinite() && edgeIntensity in 0f..3f)
    }
}

/** Hard resource bounds are not optional. Raising a slider cannot disable these limits. */
public data class LumenSurfaceSessionOptions(
    val enabled: Boolean = true,
    val maxSurfaces: Int = 16,
    val maxSoftwareBytes: Int = 8 * 1_024 * 1_024,
    val maxGpuContentPixels: Int = 4_194_304,
    val maxGpuSurfacePixels: Int = 4_194_304,
    val maxGpuDimension: Int = 4_096,
    val gpuScale: Float = 1f,
    val autoPauseWhenHidden: Boolean = true,
    val pauseWhenWindowUnfocused: Boolean = true,
    val restoreBackgroundOnDetach: Boolean = true,
    val registerMemoryCallbacks: Boolean = true,
    val diagnosticsEnabled: Boolean = true
) {
    init {
        require(maxSurfaces in 1..32)
        require(maxSoftwareBytes in 256 * 1_024..16 * 1_024 * 1_024)
        require(maxGpuContentPixels in 16_384..8_388_608)
        require(maxGpuSurfacePixels in 16_384..8_388_608)
        require(maxGpuDimension in 128..8_192)
        require(gpuScale.isFinite() && gpuScale in 0.1f..1f)
    }
}

public data class LumenSurfaceDiagnostics(
    val closed: Boolean,
    val paused: Boolean,
    val attachedSurfaces: Int,
    val gpuDraws: Long,
    val softwareDraws: Long,
    val staticDraws: Long,
    val contentRecordings: Long,
    val firstVisibleDraws: Long,
    val lastFailure: LumenSurfaceFailure,
    val paletteGeneration: Long
)

public data class LumenSurfaceState(
    val bindingId: Long,
    val requestedMaterial: LumenSurfaceMaterial,
    val effectiveMaterial: LumenSurfaceMaterial,
    val backend: LumenSurfaceBackend,
    val failure: LumenSurfaceFailure,
    val hasVisibleFrame: Boolean,
    val paletteGeneration: Long
)

/** Called outside draw/layout stacks. No user content, source paths or Uris are exposed. */
public fun interface LumenSurfaceListener {
    public fun onSurfaceState(bindingId: Long, backend: LumenSurfaceBackend, failure: LumenSurfaceFailure, firstVisibleDraw: Boolean)
}
