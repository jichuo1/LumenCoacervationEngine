package com.lumen.coacervation.engine.glow

import kotlin.math.sqrt

/** 按物理像素预滤波，先抑制细线相位混叠，再做大半径降采样模糊。 */
internal object GlowChromeBlurPolicy {
    fun prefilterRadius(radiusPx: Float): Float = if (radiusPx >= 8f) 3f else 0f

    /** Android Blur.convertRadiusToSigma；两级高斯方差相加，维持原来的总模糊宽度。 */
    fun mainRadius(radiusPx: Float): Float {
        val pre = prefilterRadius(radiusPx)
        if (pre == 0f) return radiusPx
        val sigma = radiusPx * RADIUS_TO_SIGMA + 0.5f
        val preSigma = pre * RADIUS_TO_SIGMA + 0.5f
        return (sqrt(sigma * sigma - preSigma * preSigma) - 0.5f) / RADIUS_TO_SIGMA
    }

    fun sigma(radiusPx: Float): Float = if (radiusPx > 0f) radiusPx * RADIUS_TO_SIGMA + 0.5f else 0f

    fun radiusForSigma(sigma: Float): Float = if (sigma > 0.5f) (sigma - 0.5f) / RADIUS_TO_SIGMA else 0f

    /**
     * A material-opacity level of a [fullRadiusPx] blur, as (prefilter on, main radius). The main sigma is scaled by
     * [scale] exactly, so half-octave levels stay half an octave apart after Skia's power-of-two downscale and land on
     * the same few kernel widths. Levels that no longer need the anti-alias prefilter drop it (one blur pass).
     * Returns a main radius of 0 when the level is too small to see.
     */
    fun level(fullRadiusPx: Float, scale: Float, out: FloatArray) {
        val full = prefilterRadius(fullRadiusPx) > 0f
        val mainSigma = sigma(mainRadius(fullRadiusPx)) * scale.coerceIn(0f, 1f)
        val mainRadius = radiusForSigma(mainSigma)
        val keepPrefilter = full && (scale >= 1f || mainRadius >= PREFILTER_FLOOR_PX)
        out[0] = if (keepPrefilter) 1f else 0f
        out[1] = if (mainRadius < MIN_VISIBLE_PX) 0f else mainRadius
    }

    private const val RADIUS_TO_SIGMA = 0.57735f
    /** Below this main radius the prefilter would dominate; the level is a single blur. */
    const val PREFILTER_FLOOR_PX = 8f
    /** A third of a pixel: invisible, the level becomes a pass-through. */
    const val MIN_VISIBLE_PX = 0.35f
}
