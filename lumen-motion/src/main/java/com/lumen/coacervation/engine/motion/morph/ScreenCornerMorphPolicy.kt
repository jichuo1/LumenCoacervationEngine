package com.lumen.coacervation.engine.motion.morph

import kotlin.math.abs

/** Display radii are used only where this host actually reaches the corresponding window corner. */
internal object ScreenCornerMorphPolicy {
    fun radiusInHost(index: Int, radius: Float, centerX: Float, centerY: Float,
        left: Float, top: Float, width: Float, height: Float): Float {
        if (!radius.isFinite() || radius <= 0f || !centerX.isFinite() || !centerY.isFinite() ||
            !left.isFinite() || !top.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return 0f
        val onLeft = index == 0 || index == 3
        val onTop = index < 2
        val edgeX = centerX + if (onLeft) -radius else radius
        val edgeY = centerY + if (onTop) -radius else radius
        return if (abs(edgeX - (if (onLeft) left else left + width)) <= 2f &&
            abs(edgeY - (if (onTop) top else top + height)) <= 2f) radius else 0f
    }

    fun fill(out: FloatArray, screen: FloatArray, legacyRadius: Float, expansion: Float,
        width: Float, height: Float) {
        val limit = (minOf(width, height) * .5f).coerceAtLeast(0f)
        val progress = expansion.coerceIn(0f, 1f)
        for (i in 0..3) {
            val value = screen[i]
            val target = if (value.isFinite() && value >= 0f) value else 0f
            val radius = (legacyRadius + target * progress).coerceIn(0f, limit)
            out[i * 2] = radius; out[i * 2 + 1] = radius
        }
    }
}
