package com.lumen.coacervation.engine.liquid

import android.widget.ScrollView

/** Platform ScrollView omits non-touch nested dispatch; observe only its active fling. */
internal class LiquidPlatformFling(
    private val target: ScrollView,
    private val isAllowed: () -> Boolean,
    private val absorb: (Float, LiquidStretchEdge) -> Unit
) : Runnable {
    private val handoff = LiquidFlingHandoff()
    private var direction = 0
    private var previousY = 0
    private var idleFrames = 0
    private var startedNanos = 0L

    fun start(velocityY: Float) {
        stop()
        if (!velocityY.isFinite() || velocityY == 0f) return
        handoff.start(velocityY)
        direction = if (velocityY > 0f) 1 else -1
        previousY = target.scrollY
        startedNanos = System.nanoTime()
        target.postOnAnimation(this)
    }

    override fun run() {
        if (direction == 0) return
        val now = System.nanoTime()
        if (!target.isAttachedToWindow || !target.isShown || !isAllowed() ||
            now - startedNanos >= MAX_TRACKING_NANOS) {
            stop()
            return
        }
        val currentY = target.scrollY
        val delta = currentY - previousY
        previousY = currentY
        if (delta != 0 && (delta > 0) != (direction > 0)) {
            stop()
            return
        }
        handoff.observe(delta, now)
        if (!target.canScrollVertically(direction)) {
            val edge = if (direction < 0) LiquidStretchEdge.TOP else LiquidStretchEdge.BOTTOM
            val velocity = handoff.velocity()
            stop()
            // Public fling(0) stops the exhausted scroller before recording the shared stretch.
            target.fling(0)
            absorb(velocity, edge)
            return
        }
        idleFrames = if (delta == 0) idleFrames + 1 else 0
        if (idleFrames >= MAX_IDLE_FRAMES) stop() else target.postOnAnimation(this)
    }

    fun stop() {
        target.removeCallbacks(this)
        direction = 0
        idleFrames = 0
        handoff.clear()
    }

    private companion object {
        const val MAX_IDLE_FRAMES = 3
        const val MAX_TRACKING_NANOS = 10_000_000_000L
    }
}
