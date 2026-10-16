package com.lumen.coacervation.engine.geometry

import kotlin.math.ceil
import kotlin.math.floor

/** Union in source-local coordinates, clipped before rounding outwards to complete pixels. */
internal class ViewportSamplingBounds {
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    var left = 0; private set
    var top = 0; private set
    var right = 0; private set
    var bottom = 0; private set

    fun clear() {
        minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY
    }

    fun include(left: Float, top: Float, right: Float, bottom: Float) {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            left >= right || top >= bottom) return
        minX = minOf(minX, left); minY = minOf(minY, top)
        maxX = maxOf(maxX, right); maxY = maxOf(maxY, bottom)
    }

    fun resolve(width: Int, height: Int) {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        val l = minX.coerceAtLeast(0f); val t = minY.coerceAtLeast(0f)
        val r = maxX.coerceAtMost(w.toFloat()); val b = maxY.coerceAtMost(h.toFloat())
        if (l >= r || t >= b) {
            left = 0; top = 0; right = w; bottom = h
        } else {
            left = floor(l).toInt(); top = floor(t).toInt()
            right = ceil(r).toInt().coerceAtMost(w); bottom = ceil(b).toInt().coerceAtMost(h)
        }
    }
}
