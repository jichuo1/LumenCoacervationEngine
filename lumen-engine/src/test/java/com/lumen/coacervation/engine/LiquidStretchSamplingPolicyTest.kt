package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidStretchSamplingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidStretchSamplingPolicyTest {
    @Test fun opticalFallbackMatchesNativeStretchAtBothSurfaceEdges() {
        val out = FloatArray(2)
        for (direction in listOf(-.03f, .03f)) {
            assertTrue(LiquidStretchSamplingPolicy.opticalSegment(240f, 320f, 80f, 600f, direction, out))
            for (y in listOf(240f, 320f)) {
                val expected = 80f + LiquidStretchSamplingPolicy.outputPosition((y - 80f) / 600f, direction) * 600f
                assertEquals(expected, out[0] * y + out[1], .0001f)
            }
        }
    }

    @Test fun opticalFallbackRejectsInvalidIntervalsAndKeepsAnUnstretchedIdentity() {
        val out = FloatArray(2)
        assertTrue(LiquidStretchSamplingPolicy.opticalSegment(50f, 100f, 10f, 300f, 0f, out))
        assertEquals(1f, out[0], .00001f)
        assertEquals(0f, out[1], .0001f)
        assertFalse(LiquidStretchSamplingPolicy.opticalSegment(100f, 100f, 0f, 300f, .03f, out))
        assertFalse(LiquidStretchSamplingPolicy.opticalSegment(50f, 100f, 0f, 0f, .03f, out))
        assertFalse(LiquidStretchSamplingPolicy.opticalSegment(50f, 100f, 0f, 300f, Float.NaN, out))
    }

    @Test fun distanceIsFiniteBoundedAndResetsExactly() {
        assertEquals(0f, LiquidStretchSamplingPolicy.intensity(0f), 0f)
        assertEquals(0f, LiquidStretchSamplingPolicy.intensity(Float.NaN), 0f)
        var previous = 0f
        for (step in 0..100) {
            val current = LiquidStretchSamplingPolicy.intensity(step / 100f)
            assertTrue(current >= previous && current <= .032f)
            previous = current
        }
        assertEquals(previous, LiquidStretchSamplingPolicy.intensity(100f), 0f)
    }

    @Test fun inverseReconstructsTheUnwarpedScreenCoordinateForEitherEdge() {
        for (distance in listOf(.01f, .1f, .5f, 1f)) {
            val intensity = LiquidStretchSamplingPolicy.intensity(distance)
            for (step in 0..100) {
                val screen = step / 100f
                // HWUI output -> input texture, derived independently from StretchEffect.cpp.
                val input = 1f / (1f + intensity) - (1f - screen) /
                    (1f + intensity * (1f - .7f * screen))
                assertEquals(screen, LiquidStretchSamplingPolicy.outputPosition(input, intensity), .00001f)
                assertEquals(1f - screen, LiquidStretchSamplingPolicy.outputPosition(1f - input, -intensity), .00001f)
            }
        }
    }
}
