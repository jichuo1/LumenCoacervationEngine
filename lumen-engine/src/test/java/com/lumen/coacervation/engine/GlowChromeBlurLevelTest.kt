package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.glow.GlowChromeBlurPolicy
import com.lumen.coacervation.engine.runtime.MaterialOpacityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** Material-opacity blur levels on the GPU glass (MATERIAL_OPACITY.md §14). */
class GlowChromeBlurLevelTest {
    private val out = FloatArray(2)

    /** Skia's Gaussian blur: halve the image until sigma ≤ 4, then a 1D kernel of radius ceil(3·sigma). */
    private fun kernelRadius(sigma: Float): Int {
        var s = sigma
        while (s > 4f) s /= 2f
        return ceil(3f * s).toInt()
    }

    @Test fun fullLevelIsTheConfiguredChain() {
        for (radius in floatArrayOf(4f, 7.9f, 8f, 27.5f, 132f)) {
            GlowChromeBlurPolicy.level(radius, 1f, out)
            assertEquals(GlowChromeBlurPolicy.mainRadius(radius), out[1], 1e-3f)
            assertEquals(GlowChromeBlurPolicy.prefilterRadius(radius) > 0f, out[0] > 0.5f)
        }
    }

    @Test fun mainSigmaStepsByHalfAnOctave() {
        val full = 27.5f
        var previous = Float.NaN
        for (level in MaterialOpacityPolicy.BLUR_LEVELS downTo 1) {
            GlowChromeBlurPolicy.level(full, MaterialOpacityPolicy.levelFraction(level), out)
            if (out[1] == 0f) break
            val sigma = GlowChromeBlurPolicy.sigma(out[1])
            if (!previous.isNaN()) assertEquals(Math.sqrt(2.0).toFloat(), previous / sigma, 1e-3f)
            previous = sigma
        }
    }

    @Test fun aSweepReusesFewBlurPrograms() {
        // Common radii: 10dp, 24dp and 48dp at mdpi-xxxhdpi densities.
        for (dp in floatArrayOf(10f, 24f, 48f)) for (density in floatArrayOf(2f, 2.75f, 3.5f, 4f)) {
            val kernels = HashSet<Int>()
            for (level in 1..MaterialOpacityPolicy.BLUR_LEVELS) {
                GlowChromeBlurPolicy.level(dp * density, MaterialOpacityPolicy.levelFraction(level), out)
                if (out[1] > 0f) kernels += kernelRadius(GlowChromeBlurPolicy.sigma(out[1]))
            }
            // Downscaled levels alternate between two kernel widths; small undownscaled ones add at most three.
            assertTrue("$dp dp @ $density: $kernels", kernels.size <= 5)
        }
    }

    @Test fun invisibleLevelsBecomePassThrough() {
        GlowChromeBlurPolicy.level(4f, 0.01f, out)
        assertEquals(0f, out[1], 0f)
        GlowChromeBlurPolicy.level(132f, 0.05f, out)
        // A small level drops the prefilter (single pass).
        assertEquals(0f, out[0], 0f)
    }
}
