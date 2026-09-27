package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.glow.GlowChromeBlurPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowChromeBlurPolicyTest {
    @Test fun twoStagesKeepTheRequestedGaussianVariance() {
        fun variance(radius: Float): Float = (radius * 0.57735f + 0.5f).let { it * it }
        for (radius in listOf(8f, 9f, 17f, 34f, 51f, 68f)) {
            val pre = GlowChromeBlurPolicy.prefilterRadius(radius)
            val main = GlowChromeBlurPolicy.mainRadius(radius)
            assertTrue(main > 0f && main < radius)
            assertEquals(variance(radius), variance(pre) + variance(main), 0.001f)
        }
    }
    @Test fun smallKernelsDoNotPayForAnExtraStage() {
        for (radius in listOf(1f, 3f, 6f, 7.99f)) {
            assertEquals(0f, GlowChromeBlurPolicy.prefilterRadius(radius), 0f)
            assertEquals(radius, GlowChromeBlurPolicy.mainRadius(radius), 0f)
        }
    }
}
