package com.lumen.coacervation.engine.motion

/** Converts nominal animator time to the real time used by sampled and touch velocities. */
internal object MotionTimeDomain {
    fun durationScale(value: Float): Float = if (value.isFinite() && value >= 0f) value else 1f

    fun wallDurationMs(nominalDurationMs: Long, durationScale: Float): Long {
        val nominal = nominalDurationMs.coerceAtLeast(0L)
        val scale = this.durationScale(durationScale)
        if (scale == 1f) return nominal
        // ValueAnimator also multiplies by a Float scale, then truncates to whole milliseconds.
        return (nominal * scale).toLong()
    }
}
