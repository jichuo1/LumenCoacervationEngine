package com.lumen.coacervation.engine.liquid

import kotlin.math.abs

/** Remaining fling speed from nested pre-scroll frames; no access to the child's private scroller. */
internal class LiquidFlingHandoff {
    private var launchVelocity = 0f
    private var previousFrameNanos = 0L
    private var measuredVelocity = 0f

    fun start(velocityY: Float) {
        clear()
        if (velocityY.isFinite()) launchVelocity = velocityY
    }

    fun observe(deltaPixels: Int, frameNanos: Long) {
        if (launchVelocity == 0f || deltaPixels == 0 || frameNanos <= 0L ||
            (deltaPixels > 0) != (launchVelocity > 0f)) return
        if (previousFrameNanos != 0L) {
            val elapsed = frameNanos - previousFrameNanos
            // Same-frame callbacks and sub-millisecond intervals are not velocity samples.
            if (elapsed < 1_000_000L) return
            measuredVelocity = (abs(deltaPixels.toDouble()) * 1_000_000_000.0 / elapsed)
                .coerceAtMost(abs(launchVelocity).toDouble()).toFloat()
        }
        previousFrameNanos = frameNanos
    }

    fun velocity(): Float = measuredVelocity.takeIf { it > 0f } ?: abs(launchVelocity)

    fun clear() {
        launchVelocity = 0f
        previousFrameNanos = 0L
        measuredVelocity = 0f
    }
}
