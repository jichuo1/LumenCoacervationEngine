package com.lumen.coacervation.engine.touch

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGlowOrientationTest {
    private val radius = 192f
    private val config = GlowConfig.create(3f, 36f, 1.5f, 720f, 84f, continuousEdgePile = true)
    private fun frame() = GlowFrame().apply {
        press = 1f; offsetX = 36f; centerX = 480f; centerY = 96f
        boundsWidth = 960f; boundsHeight = 192f; cornerRadius = 96f; pileRoomPx = 120f
    }
    private fun crossTerm(s: GlowShape): Float {
        val angle = s.rotationDeg * Math.PI / 180
        return ((s.radiusX * s.radiusX - s.radiusY * s.radiusY) * sin(angle) * cos(angle) / (radius * radius)).toFloat()
    }
    private fun coreY(s: GlowShape): Double {
        val angle = s.rotationDeg * Math.PI / 180
        return s.coreOffsetX * sin(angle) + s.coreOffsetY * cos(angle)
    }

    @Test fun horizontalGlowDoesNotSwivelWhenGatheringAtTheTopOrBottom() {
        for (fps in listOf(60, 90, 120)) for (side in listOf(-1f, 1f)) {
            val state = GlowState(); val f = frame()
            repeat(fps) { state.update(f, 1f / fps, radius, 72, config) }
            // 出界并聚拢，再沿原路返回；比较实际椭圆，不能仅比较等价的角度/长短轴标签。
            for (i in 0..fps * 4) {
                val distance = if (i <= fps * 2) i.toFloat() / (fps * 2) else (fps * 4 - i).toFloat() / (fps * 2)
                f.centerY = 96f + side * 280f * distance
                state.update(f, 1f / fps, radius, 72, config)
                assertEquals("No diagonal sweep at fps=$fps i=$i", 0f, crossTerm(state.shape), 0.0001f)
                assertEquals(0.0, coreY(state.shape), 0.0001)
                assertEquals(radius, sqrt(state.shape.radiusX * state.shape.radiusY), 0.02f)
            }
        }
    }

    @Test fun normalPullChangesAxesThroughARoundShapeInsteadOfSpinning() {
        val state = GlowState(); val f = frame().apply { offsetX = 0f; offsetY = -36f }
        repeat(120) { state.update(f, 1f / 120, radius, 72, config) }
        var closestToRound = Float.MAX_VALUE
        for (i in 0..480) {
            f.centerY = 96f - 280f * i / 480
            state.update(f, 1f / 120, radius, 72, config)
            val shape = state.shape
            assertEquals(0f, crossTerm(shape), 0.0001f)
            closestToRound = minOf(closestToRound, abs(shape.radiusX - shape.radiusY))
        }
        assertTrue(closestToRound < 3f)
        assertTrue(state.shape.pileUnit > 0.99f)
    }

    @Test fun roundedCornerKeepsTheCoreOnItsOriginalScreenDirection() {
        for (fps in listOf(60, 90, 120)) {
            val state = GlowState(); val f = frame()
            repeat(fps) { state.update(f, 1f / fps, radius, 72, config) }
            for (i in 0..fps * 2) {
                val t = i.toFloat() / (fps * 2)
                f.centerX = 780f + 250f * t; f.centerY = 96f - 180f * t
                state.update(f, 1f / fps, radius, 72, config)
                assertEquals(0.0, coreY(state.shape), 0.0001)
                assertEquals(radius, sqrt(state.shape.radiusX * state.shape.radiusY), 0.02f)
                assertTrue(state.shape.rotationDeg.isFinite())
            }
        }
    }
}
