package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.DrawableScenePolicy
import org.junit.Assert.*
import org.junit.Test

class DrawableScenePolicyTest {
    @Test fun halfSizeRoundsUpAndCoversTheCompleteLogicalBounds() {
        val size = DrawableScenePolicy.size(101, 203, true, .5f)
        assertEquals(51, size.width)
        assertEquals(102, size.height)
        assertEquals(.5f, size.scaleX, 0f)
        assertEquals(.5f, size.scaleY, 0f)
        assertTrue(size.width / size.scaleX >= 101f)
        assertTrue(size.height / size.scaleY >= 203f)
        val resized = DrawableScenePolicy.size(204, 102, true, .5f)
        assertEquals(size.scaleX, resized.scaleX, 0f)
        assertEquals(size.scaleY, resized.scaleY, 0f)
    }

    @Test fun softwareKeepsNativePixelsRegardlessOfGpuScale() {
        val size = DrawableScenePolicy.size(101, 203, false, .25f)
        assertEquals(101, size.width)
        assertEquals(203, size.height)
        assertEquals(1f, size.scaleX, 0f)
        assertEquals(1f, size.scaleY, 0f)
    }

    @Test fun extremeDimensionsHaveFiniteInvertibleTransformsWithoutIntegerOverflow() {
        for ((width, height) in listOf(1 to 1, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE)) {
            for (scale in listOf(.1f, .5f, 1f)) {
                val size = DrawableScenePolicy.size(width, height, true, scale)
                assertTrue(size.width in 1..width)
                assertTrue(size.height in 1..height)
                assertTrue(size.scaleX > 0f && (1f / size.scaleX).isFinite())
                assertTrue(size.scaleY > 0f && (1f / size.scaleY).isFinite())
            }
        }
    }

    @Test fun invalidScalesAndUnmeasuredDimensionsAreRejected() {
        for (scale in listOf(0f, -1f, Float.MIN_VALUE, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            expectInvalid { DrawableScenePolicy.validateScale(scale) }
        }
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 10, 10 to -1)) {
            expectInvalid { DrawableScenePolicy.size(width, height, true, .5f) }
        }
    }

    private fun expectInvalid(action: () -> Unit) {
        try { action(); fail("Expected IllegalArgumentException") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }
}
