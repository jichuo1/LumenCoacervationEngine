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

    fun softwareDivisor(width: Int, height: Int, scale: Float, pixelBudget: Int, marginPx: Int = 0): Int {
        if (width <= 0 || height <= 0) return 1
        var low = ceil(1f / scale).toInt().coerceAtLeast(1)
        if (softwareFits(width, height, marginPx, low, pixelBudget)) return low
        var high = Int.MAX_VALUE
        // At most 31 steps, including huge thin geometry. Account for each rounded padding axis.
        while (low < high) {
            val mid = low + (high - low) / 2
            if (softwareFits(width, height, marginPx, mid, pixelBudget)) high = mid else low = mid + 1
        }
        return low
    }

    private fun softwareFits(width: Int, height: Int, marginPx: Int, divisor: Int, budget: Int): Boolean {
        val margin = if (marginPx <= 0) 0L else ((marginPx.toLong() + divisor - 1) / divisor).coerceAtLeast(1L)
        val w = (width.toLong() + divisor - 1) / divisor + 2 * margin
        val h = (height.toLong() + divisor - 1) / divisor + 2 * margin
        return w <= budget.toLong() / h
    }

    fun gpuAllowed(api: Int, backend: LumenSurfaceBackend, enabled: Boolean, failed: Boolean): Boolean =
        api >= 31 && enabled && !failed && backend != LumenSurfaceBackend.SOFTWARE && backend != LumenSurfaceBackend.STATIC

    fun softwareAllowed(sampling: LumenSurfaceSampling, gpuFailed: Boolean): Boolean =
        sampling.enabled && sampling.backend != LumenSurfaceBackend.STATIC &&
            (sampling.backend == LumenSurfaceBackend.SOFTWARE || sampling.softwareFallback || !gpuFailed && sampling.backend == LumenSurfaceBackend.AUTO)
}
