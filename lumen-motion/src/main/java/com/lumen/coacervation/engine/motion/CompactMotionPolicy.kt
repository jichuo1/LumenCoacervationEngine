package com.lumen.coacervation.engine.motion

import kotlin.math.roundToInt

/** 高度作为唯一几何通道；伴随行的占位与透明度使用相同进度。 */
public object CompactMotionPolicy {
    public fun requireHeights(expandedPx: Int, compactPx: Int): Pair<Int, Int> {
        require(expandedPx > 0) { "expanded height must be positive: $expandedPx" }
        require(compactPx > 0) { "compact height must be positive: $compactPx" }
        require(expandedPx > compactPx) {
            "expanded height $expandedPx must exceed compact height $compactPx"
        }
        return expandedPx to compactPx
    }

    public fun heightAt(progress: Float, fromPx: Int, toPx: Int): Int =
        (fromPx + (toPx - fromPx) * progress.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(1)

    public fun rowAlphaAt(progress: Float, collapsing: Boolean): Float =
        progress.coerceIn(0f, 1f).let { if (collapsing) 1f - it else it }
}
