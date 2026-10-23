package com.lumen.coacervation.engine.motion.morph

import android.graphics.Path
import android.graphics.RectF

/** Reused clipping geometry. Uniform corners retain the original fast round-rectangle path. */
internal class MotionCornerShape {
    val bounds = RectF()
    val path = Path()
    val radii = FloatArray(8)
    var radius = 0f
        private set
    var asymmetric = false
        private set

    fun set(left: Float, top: Float, right: Float, bottom: Float, radiusPx: Float, corners: FloatArray?): Boolean {
        val uniform = radiusPx.coerceAtLeast(0f)
        var changed = bounds.left != left || bounds.top != top || bounds.right != right || bounds.bottom != bottom
        for (i in radii.indices) {
            val value = if (corners == null) uniform else corners[i]
            if (radii[i] != value) { radii[i] = value; changed = true }
        }
        if (!changed) return false
        bounds.set(left, top, right, bottom)
        radius = radii[0]
        asymmetric = false
        for (i in 1 until radii.size) {
            if (radii[i] != radii[0]) asymmetric = true
            radius = minOf(radius, radii[i])
        }
        path.rewind()
        if (asymmetric && !bounds.isEmpty) path.addRoundRect(bounds, radii, Path.Direction.CW)
        return true
    }

    fun clear() { bounds.setEmpty(); path.rewind(); radii.fill(0f); radius = 0f; asymmetric = false }
}
