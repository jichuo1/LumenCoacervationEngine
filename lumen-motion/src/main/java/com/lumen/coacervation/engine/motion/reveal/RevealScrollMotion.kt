package com.lumen.coacervation.engine.motion.reveal

import kotlin.math.roundToInt

/** 归请求所有的滚动帧：取消后绝不再写位置，也不影响更新的请求。 */
internal class RevealScrollMotion(
    private val currentPosition: () -> Int,
    private val setPosition: (Int) -> Unit
) {
    private var generation = 0L
    private var active = false
    private var from = 0
    private var to = 0

    fun retarget(destination: Int): Long {
        generation++
        from = currentPosition().coerceAtLeast(0)
        to = destination.coerceAtLeast(0)
        active = true
        return generation
    }

    fun frame(token: Long, fraction: Float): Boolean {
        if (!active || token != generation) return false
        val progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 1f
        setPosition((from.toDouble() + (to.toDouble() - from) * progress).roundToInt())
        return true
    }

    fun cancel() {
        generation++
        active = false
    }
}
