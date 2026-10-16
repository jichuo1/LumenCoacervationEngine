package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.geometry.ViewportSamplingBounds
import com.lumen.coacervation.engine.geometry.SamplingMatrixMath
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ViewportSamplingBoundsTest {
    private fun ViewportSamplingBounds.pixels() = intArrayOf(left, top, right, bottom)

    @Test fun unionsBlurMarginsThenClipsAndRoundsOutwards() {
        val bounds = ViewportSamplingBounds()
        bounds.include(-4.2f, 10.8f, 60.1f, 27.01f)
        bounds.include(42.9f, 80.2f, 109f, 121.4f)
        bounds.resolve(100, 120)
        assertArrayEquals(intArrayOf(0, 10, 100, 120), bounds.pixels())
    }

    @Test fun fractionalSingleRegionRetainsTheWholeBlurFootprint() {
        val bounds = ViewportSamplingBounds()
        bounds.include(12.99f, 33.01f, 66.01f, 88.99f)
        bounds.resolve(100, 120)
        assertArrayEquals(intArrayOf(12, 33, 67, 89), bounds.pixels())
    }

    @Test fun absentDisjointAndInvalidRegionsFallBackToTheSourceViewport() {
        val bounds = ViewportSamplingBounds()
        for (region in listOf(floatArrayOf(Float.NaN, 0f, 50f, 50f),
            floatArrayOf(20f, 0f, 10f, 10f), floatArrayOf(200f, 0f, 300f, 10f))) {
            bounds.clear()
            bounds.include(region[0], region[1], region[2], region[3])
            bounds.resolve(100, 120)
            assertArrayEquals(intArrayOf(0, 0, 100, 120), bounds.pixels())
        }
        bounds.clear(); bounds.resolve(0, 0)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), bounds.pixels())
    }

    @Test fun clearAllowsContinuousMovementAndSourceResizeWithoutAStaleUnion() {
        val bounds = ViewportSamplingBounds()
        repeat(100) { position ->
            bounds.clear()
            bounds.include(0f, position + .25f, 20f, position + 10.25f)
            bounds.resolve(80, 200)
            assertArrayEquals(intArrayOf(0, position, 20, position + 11), bounds.pixels())
        }
        bounds.resolve(10, 105)
        assertArrayEquals(intArrayOf(0, 99, 10, 105), bounds.pixels())
    }

    @Test fun overlayBridgeRestoresOwnerAncestryAfterRootScreenCalibration() {
        fun translation(x: Float, y: Float) = floatArrayOf(1f, 0f, x, 0f, 1f, y, 0f, 0f, 1f)
        fun multiply(a: FloatArray, b: FloatArray) = FloatArray(9).also { SamplingMatrixMath.multiply(a, b, it) }
        val owner = floatArrayOf(.82f, -.12f, 43f, .17f, .88f, 91f, 0f, 0f, 1f)
        val overlay = translation(7f, 13f)
        val inverseOverlay = translation(-7f, -13f)
        val crop = translation(16f, 22f)
        val inverseLayout = translation(-16f, -22f)
        // The platform omits owner from overlay ancestry. Proxy.getMatrix supplies
        // T(-layout) * (overlay^-1 * owner) * T(crop); the normal mapper adds layout.
        val proxy = multiply(inverseLayout, multiply(multiply(inverseOverlay, owner), crop))
        val mapped = multiply(overlay, multiply(crop, proxy))
        org.junit.Assert.assertArrayEquals(multiply(owner, crop), mapped, .0001f)
    }
}
