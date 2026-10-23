package com.lumen.coacervation.engine.motion.morph

import org.junit.Assert.*
import org.junit.Test

class ScreenCornerMorphPolicyTest {
    @Test fun matchesOnlyCornersReachedByTheHostAndSupportsDifferentRadii() {
        assertEquals(120f, ScreenCornerMorphPolicy.radiusInHost(0, 120f, 120f, 120f, 0f, 0f, 1440f, 3168f), 0f)
        assertEquals(90f, ScreenCornerMorphPolicy.radiusInHost(2, 90f, 1350f, 3078f, 0f, 0f, 1440f, 3168f), 0f)
        assertEquals(0f, ScreenCornerMorphPolicy.radiusInHost(0, 120f, 120f, 120f, 0f, 80f, 1440f, 3088f), 0f)
        assertEquals(0f, ScreenCornerMorphPolicy.radiusInHost(1, 120f, 1320f, 120f, 0f, 0f, 720f, 3168f), 0f)
    }

    @Test fun preservesTheCollapsedRadiusAndContinuouslyReachesEachDisplayRadius() {
        val screen = floatArrayOf(120f, 120f, 90f, 90f)
        val out = FloatArray(8)
        ScreenCornerMorphPolicy.fill(out, screen, 45f, 0f, 300f, 100f)
        assertArrayEquals(FloatArray(8) { 45f }, out, 0f)
        ScreenCornerMorphPolicy.fill(out, screen, 22.5f, .5f, 900f, 1600f)
        assertArrayEquals(floatArrayOf(82.5f, 82.5f, 82.5f, 82.5f, 67.5f, 67.5f, 67.5f, 67.5f), out, 0f)
        ScreenCornerMorphPolicy.fill(out, screen, 0f, 1f, 1440f, 3168f)
        assertArrayEquals(floatArrayOf(120f, 120f, 120f, 120f, 90f, 90f, 90f, 90f), out, 0f)
    }

    @Test fun unavailableDataKeepsLegacyGeometryAndRadiiStayInsideTheShape() {
        val out = FloatArray(8)
        ScreenCornerMorphPolicy.fill(out, floatArrayOf(0f, Float.NaN, -1f, Float.POSITIVE_INFINITY), 12f, .7f, 100f, 100f)
        assertArrayEquals(FloatArray(8) { 12f }, out, 0f)
        ScreenCornerMorphPolicy.fill(out, floatArrayOf(200f, 200f, 200f, 200f), 10f, 1f, 80f, 100f)
        assertArrayEquals(FloatArray(8) { 40f }, out, 0f)
    }
}
