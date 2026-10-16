package com.lumen.coacervation.engine.host

import kotlin.math.ceil

/** Rounding only; the caller owns recording dimensions and graphics budgets. */
internal object DrawableScenePolicy {
    fun validateScale(scale: Float) {
        require(scale.isFinite() && scale > 0f && scale <= 1f && (1f / scale).isFinite())
    }

    fun size(width: Int, height: Int, hardware: Boolean, scale: Float, out: Size = Size()): Size {
        require(width > 0 && height > 0)
        validateScale(scale)
        out.width = if (hardware) ceil(width.toDouble() * scale).toInt().coerceAtLeast(1) else width
        out.height = if (hardware) ceil(height.toDouble() * scale).toInt().coerceAtLeast(1) else height
        out.scaleX = if (hardware) scale else 1f
        out.scaleY = out.scaleX
        return out
    }

    class Size(var width: Int = 1, var height: Int = 1, var scaleX: Float = 1f, var scaleY: Float = 1f)
}
