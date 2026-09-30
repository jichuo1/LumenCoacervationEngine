package com.lumen.coacervation.engine.motion

import kotlin.math.abs

/**
 * 选中框从旧选项滑到新选项的运动（来源工程 JEV 灵敏度面板的"选中框连贯动画"，适配标准 §13.11）。
 *
 * 纯几何、不依赖 `android.*`：矩形用 `FloatArray(4)`（left, top, right, bottom），逐帧零分配。
 *
 * - **静止起步**：沿来源工程同一条强调减速曲线 `cubic-bezier(0.2, 0, 0, 1)` 走完 [DURATION_MS]，
 *   与来源逐帧一致。
 * - **中途改目标**（动画未完又点了别的选项）：从**当前渲染位置与当前速度**续接，而不是像来源那样
 *   从零速度重新起步——那会让选中框在改向瞬间先停住再加速。续接用引擎的 [InterruptibleMotionContinuation]：
 *   切线有界、同向不过冲，反向时不带反向动量（从静止起步），不会甩过目标。
 * - **目标在运动中移动**（行高因折行或面板仍在展开而变化）：调用方每帧用行的实时几何更新 [target]，
 *   插值基于实时目标，选中框收尾时恰好落在行上。
 */
internal class SlidingSelectionMotion(private val durationMs: Long = DURATION_MS) {
    private val from = FloatArray(4)
    /** 终点矩形；调用方每帧可以改写（跟随行的实时几何）。 */
    val target = FloatArray(4)
    private var continuation: InterruptibleMotionContinuation? = null

    /** 从 [current]（当前渲染矩形）开始，以每条边的速度 [edgeVelocityPxPerSec] 滑向 [destination]。 */
    fun start(current: FloatArray, edgeVelocityPxPerSec: FloatArray, destination: FloatArray) {
        current.copyInto(from)
        destination.copyInto(target)
        // 以位移最大的一条边定义归一化进度；速度沿这条边折算成"每秒进度"。
        var axis = 0
        for (edge in 1..3) if (abs(target[edge] - from[edge]) > abs(target[axis] - from[axis])) axis = edge
        val delta = target[axis] - from[axis]
        val normalizedVelocity = if (abs(delta) < MIN_TRAVEL_PX) 0f else edgeVelocityPxPerSec[axis] / delta
        continuation = if (abs(normalizedVelocity) < MIN_NORMALIZED_VELOCITY) null
        else InterruptibleMotionContinuation(0f, 1f, normalizedVelocity, durationMs)
    }

    /** 线性时间比例 [fraction]（0..1）对应的进度。 */
    fun progress(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return continuation?.value(t) ?: EMPHASIZED_DECELERATE.value(t)
    }

    /** 写出 [fraction] 时刻的矩形。 */
    fun frame(fraction: Float, out: FloatArray) {
        val p = progress(fraction)
        for (edge in 0..3) out[edge] = from[edge] + (target[edge] - from[edge]) * p
    }

    /** 每条边在 [fraction] 时刻的速度（px/s），供下一次改目标续接。数值差分，零分配。 */
    fun edgeVelocity(fraction: Float, out: FloatArray) {
        val t = fraction.coerceIn(0f, 1f)
        if (t >= 1f) {
            out.fill(0f)
            return
        }
        val step = VELOCITY_STEP.coerceAtMost(1f - t)
        val dp = (progress(t + step) - progress(t)) / (step * durationMs / 1000f)
        for (edge in 0..3) out[edge] = (target[edge] - from[edge]) * dp
    }

    companion object {
        /** 来源工程的选中框滑动时长。 */
        const val DURATION_MS = 260L
        /** 来源工程与 `LumenEasing.emphasizedDecelerate()` 同一条曲线。 */
        val EMPHASIZED_DECELERATE = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        private const val MIN_TRAVEL_PX = 0.5f
        private const val MIN_NORMALIZED_VELOCITY = 0.05f
        private const val VELOCITY_STEP = 1f / 120f
    }
}

/** 选项高亮：选中框沿排列方向盖住这一行的比例。 */
internal object SlidingSelectionHighlight {
    /** [indicatorStart]..[indicatorEnd] 覆盖 [rowStart]..[rowEnd] 的比例，0..1；行长度为 0 时返回 0。 */
    fun coverage(indicatorStart: Float, indicatorEnd: Float, rowStart: Float, rowEnd: Float): Float {
        val length = rowEnd - rowStart
        if (length <= 0f) return 0f
        val overlap = minOf(indicatorEnd, rowEnd) - maxOf(indicatorStart, rowStart)
        return (overlap / length).coerceIn(0f, 1f)
    }
}

/**
 * CSS 语义的三次贝塞尔缓动（端点固定为 (0,0) 与 (1,1)），与 `PathInterpolator(x1, y1, x2, y2)` 等价，
 * 但不依赖 `android.*`，可以在 JVM 上单测。求值先牛顿迭代、不收敛时退回二分，零分配。
 */
internal class CubicBezierEasing(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float
) {
    init {
        require(x1 in 0f..1f && x2 in 0f..1f) { "control x must be in [0, 1]" }
    }

    fun value(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        return sample(y1, y2, solveT(x))
    }

    private fun solveT(x: Float): Float {
        var t = x
        repeat(8) {
            val error = sample(x1, x2, t) - x
            if (abs(error) < EPSILON) return t
            val slope = slope(x1, x2, t)
            if (abs(slope) < 1e-6f) return@repeat
            t -= error / slope
        }
        var low = 0f
        var high = 1f
        t = x
        repeat(40) {
            val value = sample(x1, x2, t)
            if (abs(value - x) < EPSILON) return t
            if (value < x) low = t else high = t
            t = (low + high) / 2f
        }
        return t
    }

    private fun sample(p1: Float, p2: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }

    private fun slope(p1: Float, p2: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2)
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
