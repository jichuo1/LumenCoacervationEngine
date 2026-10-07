package com.lumen.coacervation.engine.host

import kotlin.math.ceil
import kotlin.math.sqrt

/** Pure geometry, budgeting and degradation rules shared by both capture backends. */
internal object LumenSurfacePolicy {
    fun fade(fraction: Float, hold: Float, end: Float, reverse: Boolean): Float {
        val x = if (reverse) 1f - fraction else fraction
        if (x <= hold) return 1f
        if (x >= end || end <= hold) return 0f
        val t = ((x - hold) / (end - hold)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    fun gpuScale(width: Int, height: Int, desired: Float, pixelBudget: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        return minOf(desired, sqrt(pixelBudget.toDouble() / (width.toDouble() * height)).toFloat(), pixelBudget.toFloat() / maxOf(width, height))
    }

    fun softwareDivisor(width: Int, height: Int, scale: Float, pixelBudget: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var divisor = maxOf(ceil(1f / scale).toInt(), ceil(sqrt(width.toDouble() * height / pixelBudget)).toInt(), 1)
        // Integer rounding must obey the budget even for a very thin surface.
        while (((width.toLong() + divisor - 1) / divisor) * ((height.toLong() + divisor - 1) / divisor) > pixelBudget) divisor++
        return divisor
    }

    fun gpuAllowed(api: Int, backend: LumenSurfaceBackend, enabled: Boolean, failed: Boolean): Boolean =
        api >= 31 && enabled && !failed && backend != LumenSurfaceBackend.SOFTWARE && backend != LumenSurfaceBackend.STATIC

    fun softwareAllowed(sampling: LumenSurfaceSampling, gpuFailed: Boolean): Boolean =
        sampling.enabled && sampling.backend != LumenSurfaceBackend.STATIC &&
            (sampling.backend == LumenSurfaceBackend.SOFTWARE || sampling.softwareFallback || !gpuFailed && sampling.backend == LumenSurfaceBackend.AUTO)
}
