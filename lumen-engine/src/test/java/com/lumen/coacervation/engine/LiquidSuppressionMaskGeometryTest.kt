package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidSuppressionMaskGeometry
import org.junit.Assert.*
import org.junit.Test

class LiquidSuppressionMaskGeometryTest {
    private data class Shape(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val rx: Float, val ry: Float = rx
    ) {
        fun contains(x: Float, y: Float): Boolean {
            if (x < left || x > right || y < top || y > bottom) return false
            val rX = rx.coerceIn(0f, (right - left) / 2f)
            val rY = ry.coerceIn(0f, (bottom - top) / 2f)
            if (rX == 0f || rY == 0f) return true
            val dx = (x - x.coerceIn(left + rX, right - rX)) / rX
            val dy = (y - y.coerceIn(top + rY, bottom - rY)) / rY
            return dx * dx + dy * dy <= 1.00001f
        }
    }

    private fun LiquidSuppressionMaskGeometry.shape() = Shape(left, top, right, bottom, radiusX, radiusY)

    @Test fun reproducesTheOldFullScreenCornerHoleAndCoversAllFourCornersAfterFix() {
        for (density in listOf(1f, 2.75f, 3.5f, 4f)) {
            for (paddingDp in listOf(12f, 22f)) for (scale in listOf(0.25f, 0.47f, 1f)) {
                val padding = paddingDp * density
                val mask = LiquidSuppressionMaskGeometry()
                assertTrue(mask.set(0f, 0f, 1440f, 3000f, 0f, padding, 1440, 3000, scale, scale))
                val old = Shape(0f, 0f, 1440f * scale, 3000f * scale, padding * scale)
                for (x in listOf(0f, 1440f * scale)) for (y in listOf(0f, 3000f * scale)) {
                    assertFalse("Old clipping leaves the optical output unmasked", old.contains(x, y))
                    assertTrue("Full-screen optical output must be entirely replaced", mask.shape().contains(x, y))
                }
            }
        }
    }

    @Test fun centeredCardsKeepExactlyTheExistingMaskGeometry() {
        val mask = LiquidSuppressionMaskGeometry()
        assertTrue(mask.set(100f, 200f, 900f, 600f, 52f, 22f, 1440, 3000, 0.5f, 0.5f))
        assertEquals(Shape(39f, 89f, 461f, 311f, 37f), mask.shape())
    }

    @Test fun clippedRoundedCardsRetainTheirOriginalCornerCenters() {
        val mask = LiquidSuppressionMaskGeometry()
        assertTrue(mask.set(-40f, -20f, 400f, 300f, 60f, 22f, 360, 640, 0.5f, 0.25f))
        assertEquals((-40f + 60f) * 0.5f, mask.left + mask.radiusX, 0f)
        assertEquals((-20f + 60f) * 0.25f, mask.top + mask.radiusY, 0f)
        assertEquals((400f - 60f) * 0.5f, mask.right - mask.radiusX, 0f)
        assertTrue(mask.left < 0f)
        assertTrue(mask.right > 360f * 0.5f)
    }

    @Test fun everyVisibleOpticalPixelRemainsCoveredThroughEntryAndPredictiveBack() {
        val mask = LiquidSuppressionMaskGeometry()
        for (step in 0..100) {
            val p = step / 100f
            val surface = Shape(20f * (1f - p), 160f * (1f - p), 340f + 20f * p,
                240f + 400f * p, 28f * (1f - p))
            assertTrue(mask.set(surface.left, surface.top, surface.right, surface.bottom,
                surface.rx, 22f, 360, 640, 0.47f, 0.51f))
            for (x in 0..360 step 5) for (y in 0..640 step 5) {
                if (surface.contains(x.toFloat(), y.toFloat())) {
                    assertTrue("Unmasked output at p=$p, ($x,$y)", mask.shape().contains(x * 0.47f, y * 0.51f))
                }
            }
        }
    }

    @Test fun partialOffscreenSurfacesStayCoveredOnEveryEdge() {
        for (surface in listOf(
            Shape(-50f, 40f, 150f, 240f, 50f), Shape(210f, 40f, 410f, 240f, 50f),
            Shape(40f, -50f, 240f, 150f, 50f), Shape(40f, 540f, 240f, 740f, 50f)
        )) {
            val mask = LiquidSuppressionMaskGeometry()
            assertTrue(mask.set(surface.left, surface.top, surface.right, surface.bottom,
                surface.rx, 22f, 360, 640, 1f, 1f))
            for (x in 0..360 step 3) for (y in 0..640 step 3) {
                if (surface.contains(x.toFloat(), y.toFloat())) assertTrue(mask.shape().contains(x.toFloat(), y.toFloat()))
            }
        }
    }

    @Test fun pixelsOutsideTheEffectFootprintRemainLive() {
        val mask = LiquidSuppressionMaskGeometry()
        assertTrue(mask.set(100f, 100f, 200f, 200f, 20f, 10f, 360, 640, 1f, 1f))
        for ((x, y) in listOf(89f to 150f, 211f to 150f, 150f to 89f, 150f to 211f)) {
            assertFalse(mask.shape().contains(x, y))
        }
    }

    @Test fun onlyOffscreenExpandedFootprintsAreSkipped() {
        val mask = LiquidSuppressionMaskGeometry()
        assertFalse(mask.set(-100f, 100f, -30f, 200f, 0f, 22f, 360, 640, 1f, 1f))
        assertTrue(mask.set(-100f, 100f, -10f, 200f, 0f, 22f, 360, 640, 1f, 1f))
        assertTrue(mask.shape().contains(0f, 150f))
    }

    @Test fun zeroPaddingAndLargeCornerRadiusMatchTheRenderedRoundRect() {
        val mask = LiquidSuppressionMaskGeometry()
        assertTrue(mask.set(10f, 20f, 110f, 60f, 100f, 0f, 360, 640, 1f, 1f))
        assertEquals(Shape(10f, 20f, 110f, 60f, 20f), mask.shape())
    }

    @Test fun emptySurfacesAndZeroSizeCapturesDoNotCreateMasks() {
        val mask = LiquidSuppressionMaskGeometry()
        assertFalse(mask.set(0f, 0f, 0f, 100f, 0f, 22f, 360, 640, 1f, 1f))
        assertFalse(mask.set(0f, 0f, 100f, 100f, 0f, 22f, 0, 640, 1f, 1f))
        assertFalse(mask.set(0f, 0f, 100f, 100f, 0f, 22f, 360, 640, 0f, 1f))
    }
}
