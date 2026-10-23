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

    @Test fun `cropped visible edges fit the panel using the original source pivot`() {
        for (cropTop in listOf(16f, 48f)) {
            val sourceHeight = 60f
            val sourcePivot = 30f
            val visibleHeight = sourceHeight - cropTop
            val baselineSourceTop = 20f - cropTop
            ElasticClipGeometry.fitAxis(-80f, 1.025f, visibleHeight, sourcePivot - cropTop, 20f, 80f, out)
            val visibleTop = baselineSourceTop + out.x + cropTop * out.y + sourcePivot * (1f - out.y)
            assertEquals("Only the already visible portion determines the retained exterior edge", 0f, visibleTop, .0001f)
            assertTrue("Invisible source pixels are left to the owned local crop", baselineSourceTop + out.x < 0f)
        }
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

    @Test fun `bottom stretch cannot turn downward travel upward`() {
        ElasticClipGeometry.fitAxis(14.56f, 1f + 2.5f / 600f, 600f, 300f, 80f, 0f, out)
        assertEquals(0f, out.x, 0f)
        assertEquals(1f, out.y, 0f)
        ElasticClipGeometry.fitAxis(-14.56f, 1f + 2.5f / 600f, 600f, 300f, 80f, 0f, out)
        assertEquals(-14.56f, out.x, 0f)
        assertEquals(1f + 2.5f / 600f, out.y, 0f)
    }

    @Test fun `zero input never becomes a clip compensation and signed input never reverses`() {
        for (size in listOf(40f, 300f, 1600f)) for (pivot in listOf(-24f, 0f, size / 2, size, size + 24f)) {
            for (leading in listOf(0f, 4f, 24f)) for (trailing in listOf(0f, 4f, 24f)) {
                for (translation in listOf(-30f, -1f, 0f, 1f, 30f)) for (scale in listOf(.98f, 1f, 1.02f, 1.1f)) {
                    ElasticClipGeometry.fitAxis(translation, scale, size, pivot, leading, trailing, out)
                    assertTrue("Input $translation became ${out.x}", when {
                        translation > 0 -> out.x >= 0f
                        translation < 0 -> out.x <= 0f
                        else -> out.x == 0f
                    })
                    val left = out.x + pivot * (1f - out.y)
                    assertTrue("left=$left", left >= -leading - .001f)
                    assertTrue("right=${left + size * out.y}", left + size * out.y <= size + trailing + .001f)
                }
            }
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
