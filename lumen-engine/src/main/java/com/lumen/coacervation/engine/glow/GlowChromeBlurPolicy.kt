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

    private const val RADIUS_TO_SIGMA = 0.57735f
}
