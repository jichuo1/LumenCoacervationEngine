package com.lumen.coacervation.engine.interaction

import org.junit.Assert.*
import org.junit.Test

class ElasticClipGeometryTest {
    private val out = ElasticVector()

    @Test fun `rounded corner constrains a diagonal that fits the bounding rectangle`() {
        val clips = floatArrayOf(-12f, -12f, 288f, 108f, 24f)
        val factor = ElasticClipGeometry.roundedFraction(-10f, -10f, 1f, 1f,
            100f, 40f, 50f, 20f, clips, 1)
        assertTrue(factor > 0f && factor < 1f)
        val x = -10f * factor
        val y = -10f * factor
        assertTrue(ElasticClipGeometry.roundedContains(x, y, x + 100f, y + 40f,
            -12f, -12f, 288f, 108f, 24f))
    }

    @Test fun `rounded room preserves the original deformation`() {
        val clips = floatArrayOf(-40f, -40f, 340f, 140f, 24f)
        assertEquals(1f, ElasticClipGeometry.roundedFraction(-18f, -18f, 1.02f, 1.02f,
            300f, 100f, 150f, 50f, clips, 1), 0f)
    }

    @Test fun `an already cropped outline keeps the baseline when moving further outside`() {
        val clips = floatArrayOf(0f, 0f, 300f, 120f, 24f)
        assertEquals(0f, ElasticClipGeometry.roundedFraction(-10f, -10f, 1.01f, 1.01f,
            100f, 40f, 50f, 20f, clips, 1), 0f)
    }

    @Test fun `full width row cannot translate or grow outside its viewport`() {
        ElasticClipGeometry.fitAxis(-18f, 1.025f, 300f, 150f, 0f, 0f, out)
        assertEquals(0f, out.x, 0f)
        assertEquals(1f, out.y, 0f)
    }

    @Test fun `press contraction leaves a small continuous travel range`() {
        ElasticClipGeometry.fitAxis(-18f, .984f, 300f, 150f, 0f, 0f, out)
        assertEquals(.984f, out.y, 0f)
        assertTrue(out.x < 0f)
        assertEquals(0f, out.x + 150f * (1f - out.y), .0001f)
    }

    @Test fun `roomy controls preserve both travel and stretch`() {
        ElasticClipGeometry.fitAxis(-18f, 1.025f, 80f, 40f, 24f, 24f, out)
        assertEquals(-18f, out.x, 0f)
        assertEquals(1.025f, out.y, 0f)
    }

    @Test fun `edge fitting includes an off center pivot`() {
        ElasticClipGeometry.fitAxis(18f, 1.1f, 100f, 20f, 4f, 6f, out)
        val left = out.x + 20f * (1f - out.y)
        assertTrue(left >= -4.0001f)
        assertTrue(left + 100f * out.y <= 106.0001f)
    }

    @Test fun `partial baseline clipping does not shift the row into view`() {
        ElasticClipGeometry.fitAxis(0f, 1f, 300f, 150f, -12f, 20f, out)
        assertEquals(0f, out.x, 0f)
        assertEquals(1f, out.y, 0f)
    }

    @Test fun `unbounded ordinary wrappers keep original visual response`() {
        ElasticClipGeometry.fitAxis(18f, 1.03f, 300f, 150f,
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, out)
        assertEquals(18f, out.x, 0f)
        assertEquals(1.03f, out.y, 0f)
    }

    @Test fun `invalid inputs restore a finite baseline`() {
        for (input in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            ElasticClipGeometry.fitAxis(input, 1f, 100f, 50f, 0f, 0f, out)
            assertEquals(0f, out.x, 0f)
            assertEquals(1f, out.y, 0f)
        }
    }

    @Test fun `fitted edges stay contained across drag and spring overshoot`() {
        for (size in listOf(40f, 300f, 1600f)) {
            for (pivot in listOf(0f, size / 2, size)) {
                for (leftGap in listOf(0f, 4f, 24f)) {
                    for (rightGap in listOf(0f, 4f, 24f)) {
                        for (translation in listOf(-30f, -18f, 0f, 18f, 30f)) {
                            for (scale in listOf(.98f, 1f, 1.02f, 1.1f)) {
                                ElasticClipGeometry.fitAxis(translation, scale, size, pivot,
                                    leftGap, rightGap, out)
                                val left = out.x + pivot * (1f - out.y)
                                val right = left + size * out.y
                                assertTrue("left=$left", left >= -leftGap - .001f)
                                assertTrue("right=$right", right <= size + rightGap + .001f)
                            }
                        }
                    }
                }
            }
        }
    }
}
