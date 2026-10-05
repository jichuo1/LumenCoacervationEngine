package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidStretchSamplingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidStretchSamplingPolicyTest {
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
