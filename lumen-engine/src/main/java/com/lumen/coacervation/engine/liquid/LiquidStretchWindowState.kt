package com.lumen.coacervation.engine.liquid

import kotlin.math.abs

/** Read-only optical state for one window; updated by [LiquidStretchWindowRegistry]. */
public class LiquidStretchWindowState internal constructor() {
    public var distance: Float = 0f
        private set
    public var opticalIntensity: Float = 1f
        private set
    public var edgeDirection: Float = 0f
        private set

    internal fun update(nextDistance: Float, nextIntensity: Float, nextDirection: Float): Boolean {
        val boundedDistance = if (nextDistance.isFinite()) nextDistance.coerceAtLeast(0f) else 0f
        val boundedIntensity = if (nextIntensity.isFinite()) nextIntensity.coerceAtLeast(1f) else 1f
        val boundedDirection = if (nextDirection.isFinite()) nextDirection.coerceIn(-1f, 1f) else 0f
        val samplingChanged = abs(boundedDistance - distance) >= .0005f || boundedDistance == 0f && distance != 0f
        if (abs(boundedIntensity - opticalIntensity) < .004f && boundedDirection == edgeDirection && !samplingChanged) return false
        distance = boundedDistance
        opticalIntensity = boundedIntensity
        edgeDirection = boundedDirection
        return true
    }
}
