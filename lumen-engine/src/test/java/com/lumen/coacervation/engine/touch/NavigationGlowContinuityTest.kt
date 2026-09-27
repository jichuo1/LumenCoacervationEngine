package com.lumen.coacervation.engine.touch

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGlowContinuityTest {
    private val radius = 192f
    private fun config(continuous: Boolean) = GlowConfig.create(
        density = 3f, maxTravelPx = 36f, travelEpsPx = 1.5f,
        velocityRefPxPerSec = 720f, edgeBandPx = 84f, continuousEdgePile = continuous
    )
    private fun frame() = GlowFrame().apply {
        press = 1f; offsetY = -36f; centerX = 480f; centerY = 96f
        boundsWidth = 960f; boundsHeight = 192f; cornerRadius = 96f; pileRoomPx = 120f
    }

    @Test fun crossingTheEdgeDoesNotShrinkBeforePileStarts() {
        val state = GlowState(); val f = frame(); val cfg = config(true)
        repeat(240) { state.update(f, 1f / 120, radius, 72, cfg) }
        val insideAlpha = state.shape.alphaUnit
        for (y in 96 downTo -6) {
            f.centerY = y.toFloat()
            repeat(4) { state.update(f, 1f / 120, radius, 72, cfg) }
            assertEquals(radius, sqrt(state.shape.radiusX * state.shape.radiusY), 0.01f)
            assertTrue("No dim valley at y=$y", state.shape.alphaUnit >= insideAlpha - 0.001f)
        }
    }

    @Test fun continuousGestureKeepsItsScaleThroughPileAtAllRefreshRates() {
        for (fps in listOf(60, 90, 120)) {
            val state = GlowState(); val f = frame(); val cfg = config(true)
            repeat(fps) { state.update(f, 1f / fps, radius, 72, cfg) }
            var alpha = state.shape.alphaUnit
            for (i in 0..fps * 2) {
                f.centerY = 96f - 250f * i / (fps * 2)
                state.update(f, 1f / fps, radius, 72, cfg)
                assertEquals(radius, sqrt(state.shape.radiusX * state.shape.radiusY), 0.02f)
                assertTrue(state.shape.alphaUnit >= alpha - 0.001f)
                assertTrue(state.shape.visible)
                alpha = state.shape.alphaUnit
            }
            assertTrue(state.shape.pileUnit > 0.9f)
        }
    }

    @Test fun fullPileKeepsThePreviouslyTunedBrightness() {
        fun settled(continuous: Boolean): Float {
            val state = GlowState(); val f = frame().apply { centerY = -300f }
            repeat(360) { state.update(f, 1f / 120, radius, 72, config(continuous)) }
            return state.shape.alphaUnit
        }
        assertEquals(settled(false), settled(true), 0.001f)
    }
}
