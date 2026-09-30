package com.lumen.coacervation.engine.motion

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidingSelectionMotionTest {
    private fun rect(left: Float, top: Float, right: Float, bottom: Float) = floatArrayOf(left, top, right, bottom)
    private val still = FloatArray(4)

    /** 独立实现：按参数 t 采样贝塞尔曲线，再线性查表，用来核对牛顿/二分求解。 */
    private fun bruteForce(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        fun b(p1: Float, p2: Float, t: Double) = 3 * (1 - t) * (1 - t) * t * p1 + 3 * (1 - t) * t * t * p2 + t * t * t
        var previousT = 0.0
        var previousX = 0.0
        val steps = 200_000
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val bx = b(x1, x2, t)
            if (bx >= x) {
                val tt = previousT + (t - previousT) * (x - previousX) / (bx - previousX)
                return b(y1, y2, tt).toFloat()
            }
            previousT = t
            previousX = bx
        }
        return 1f
    }

    @Test fun emphasizedCurveMatchesTheSourceControlPoints() {
        val curve = SlidingSelectionMotion.EMPHASIZED_DECELERATE
        assertEquals(0f, curve.value(0f), 0f)
        assertEquals(1f, curve.value(1f), 0f)
        var previous = 0f
        for (i in 1..100) {
            val x = i / 100f
            val y = curve.value(x)
            assertEquals(bruteForce(0.2f, 0f, 0f, 1f, x), y, 2e-4f)
            assertTrue("monotonic at $x", y >= previous - 1e-6f)
            previous = y
        }
    }

    @Test fun freshStartFollowsTheSourceCurveFromOldRowToNewRow() {
        val motion = SlidingSelectionMotion()
        val from = rect(0f, 0f, 300f, 48f)
        val to = rect(0f, 104f, 300f, 172f)
        motion.start(from, still, to)
        val out = FloatArray(4)
        motion.frame(0f, out)
        assertTrue(out.contentEquals(from))
        motion.frame(1f, out)
        assertTrue(out.contentEquals(to))
        // 中途：位置与高度同一条曲线插值（来源 place(top, height) 的行为）。
        motion.frame(0.5f, out)
        val p = SlidingSelectionMotion.EMPHASIZED_DECELERATE.value(0.5f)
        assertEquals(104f * p, out[1], 1e-3f)
        assertEquals(48f + (172f - 48f) * p, out[3], 1e-3f)
        // 不过冲。
        for (i in 0..100) {
            motion.frame(i / 100f, out)
            assertTrue(out[1] in -1e-3f..104.001f)
        }
    }

    @Test fun retargetMidFlightIsContinuousInPositionAndVelocity() {
        val motion = SlidingSelectionMotion()
        val a = rect(0f, 0f, 300f, 48f)
        val b = rect(0f, 104f, 300f, 152f)
        val c = rect(0f, 208f, 300f, 256f)
        motion.start(a, still, b)
        val at = 0.3f
        val current = FloatArray(4).also { motion.frame(at, it) }
        val velocity = FloatArray(4).also { motion.edgeVelocity(at, it) }
        assertTrue("moving down", velocity[1] > 0f)
        // 同方向改到更远的 C：从当前位置、当前速度续接。
        val next = SlidingSelectionMotion()
        next.start(current, velocity, c)
        val start = FloatArray(4).also { next.frame(0f, it) }
        assertTrue(start.contentEquals(current))
        val nextVelocity = FloatArray(4).also { next.edgeVelocity(0f, it) }
        assertEquals(velocity[1], nextVelocity[1], abs(velocity[1]) * 0.08f)
        val end = FloatArray(4).also { next.frame(1f, it) }
        assertTrue(end.contentEquals(c))
    }

    @Test fun reversingDoesNotFlingPastTheNewTarget() {
        val motion = SlidingSelectionMotion()
        val a = rect(0f, 0f, 300f, 48f)
        val c = rect(0f, 208f, 300f, 256f)
        motion.start(a, still, c)
        val current = FloatArray(4).also { motion.frame(0.4f, it) }
        val velocity = FloatArray(4).also { motion.edgeVelocity(0.4f, it) }
        // 往回点 A：反向不带动量，全程在 [A, 当前位置] 之间单调回落。
        val back = SlidingSelectionMotion()
        back.start(current, velocity, a)
        val out = FloatArray(4)
        var previous = current[1]
        for (i in 0..100) {
            back.frame(i / 100f, out)
            assertTrue(out[1] <= current[1] + 1e-3f && out[1] >= -1e-3f)
            assertTrue(out[1] <= previous + 1e-3f)
            previous = out[1]
        }
    }

    @Test fun targetMovingDuringTheSlideIsFollowed() {
        val motion = SlidingSelectionMotion()
        motion.start(rect(0f, 0f, 300f, 48f), still, rect(0f, 104f, 300f, 152f))
        // 行在滑动途中因折行变高：调用方改写 target，收尾落在新几何上。
        rect(0f, 104f, 300f, 190f).copyInto(motion.target)
        val out = FloatArray(4).also { motion.frame(1f, it) }
        assertEquals(190f, out[3], 0f)
    }

    @Test fun zeroDistanceAndFinishedStatesAreStill() {
        val motion = SlidingSelectionMotion()
        val a = rect(0f, 0f, 300f, 48f)
        motion.start(a, still, a)
        val out = FloatArray(4)
        for (i in 0..10) {
            motion.frame(i / 10f, out)
            assertTrue(out.contentEquals(a))
        }
        motion.start(a, still, rect(0f, 104f, 300f, 152f))
        motion.edgeVelocity(1f, out)
        assertTrue(out.all { it == 0f })
    }

    @Test fun highlightIsTheIndicatorsCoverageOfEachRow() {
        assertEquals(1f, SlidingSelectionHighlight.coverage(0f, 48f, 0f, 48f), 0f)
        assertEquals(0.5f, SlidingSelectionHighlight.coverage(24f, 72f, 0f, 48f), 0f)
        assertEquals(0f, SlidingSelectionHighlight.coverage(52f, 100f, 0f, 48f), 0f)
        // 选中框比行高（从高行滑向矮行）时，行被完全盖住即为 1。
        assertEquals(1f, SlidingSelectionHighlight.coverage(-10f, 80f, 0f, 48f), 0f)
        assertEquals(0f, SlidingSelectionHighlight.coverage(0f, 48f, 10f, 10f), 0f)
    }

    /** 来源每帧改 layoutParams.height（整棵树逐帧重新布局）；移植后逐帧只允许直接 layout() 选中框。 */
    @Test fun widgetFramePathNeverRequestsLayoutAndCleansUp() {
        val source = MotionSource.file("LumenSlidingSelection")
        val frame = source.after("private fun renderFrame(").before("private fun readTarget(")
        assertFalse(frame.contains("requestLayout("))
        assertFalse(frame.contains("layoutParams ="))
        assertTrue(frame.contains("indicator.layout("))
        assertTrue(frame.contains("notifyPositionChanged()"))
        val detach = source.after("override fun onDetachedFromWindow()").before("private fun renderFrame(")
        assertTrue(detach.contains("stopAnimation()"))
        assertTrue(detach.contains("stopObservingTree()"))
        assertTrue(source.contains("removeOnPreDrawListener(followSelectedRow)"))
        // 续接要用"当前速度"，不能像来源那样每次从零速度起步。
        val select = source.after("public fun select(").before("override fun onLayout(")
        assertTrue(select.contains("motion.edgeVelocity(fraction, velocity)"))
        assertTrue(select.contains("ValueAnimator.areAnimatorsEnabled()"))
        // 逐帧回调不装箱：高亮回调必须是原始类型参数的 fun interface，不能是 (Int, Float) -> Unit。
        assertTrue(source.contains("public fun onHighlight(index: Int, weight: Float)"))
        assertFalse(source.contains("(index: Int, weight: Float) -> Unit"))
    }
}
