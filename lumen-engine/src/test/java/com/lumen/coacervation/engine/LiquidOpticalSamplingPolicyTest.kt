package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidBackdropSizingPolicy
import com.lumen.coacervation.engine.liquid.LiquidOpticalSamplingPolicy
import com.lumen.coacervation.engine.liquid.LiquidSurfaceEdgePolicy
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.*
import org.junit.Test

class LiquidOpticalSamplingPolicyTest {
    @Test fun opticalCopyAndPreparationMemoryRemainBoundedForAllSupportedSourceSizes() {
        for ((width, height) in listOf(1 to 1, 1080 to 2400, 1440 to 3200, 3840 to 2160,
            1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val sample = LiquidBackdropSizingPolicy.resolve(width, height)
            val bytes = LiquidOpticalSamplingPolicy.opticalBytes(sample.width, sample.height)
            assertTrue(bytes <= 2L * 1024L * 1024L)
            assertTrue(bytes * 4L <= LiquidOpticalSamplingPolicy.MAX_PREPARATION_BYTES)
        }
        assertTrue(runCatching { LiquidOpticalSamplingPolicy.opticalBytes(0, 10) }.isFailure)
        assertTrue(runCatching { LiquidOpticalSamplingPolicy.opticalBytes(Int.MAX_VALUE, Int.MAX_VALUE) }.isFailure)
    }

    @Test fun sampledRadiusRepresentsPhysicalDiffusionRatherThanOneFixedPixelRadius() {
        assertEquals(15, LiquidOpticalSamplingPolicy.sampledRadius(270, 1080, 3f))
        assertEquals(18, LiquidOpticalSamplingPolicy.sampledRadius(360, 1440, 3.5f))
        assertEquals(5, LiquidOpticalSamplingPolicy.sampledRadius(360, 1440, 1f))
        assertEquals(10, LiquidOpticalSamplingPolicy.sampledRadius(540, 1080, 1f))
        assertEquals(24, LiquidOpticalSamplingPolicy.sampledRadius(2048, 2048, 4f))
        assertTrue(runCatching { LiquidOpticalSamplingPolicy.sampledRadius(1, 1, Float.NaN) }.isFailure)
        assertTrue(runCatching { LiquidOpticalSamplingPolicy.sampledRadius(1, 0, 1f) }.isFailure)
    }

    @Test fun opticalFilteringRemovesDetailWithoutChangingTheRootImagePixels() {
        val width = 65
        val height = 33
        val original = IntArray(width * height) { pixel ->
            if ((pixel % width + pixel / width) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val before = original.copyOf()
        val optical = LiquidOpticalSamplingPolicy.soften(original, width, height, width * 4, 2f)
        assertArrayEquals(before, original)
        assertNotSame(original, optical)
        assertEquals(original.size, optical.size)
        assertTrue(optical.all { it ushr 24 == 255 })
        val center = (10..22).flatMap { y -> (20..44).map { x -> optical[y * width + x] and 255 } }
        assertTrue(center.max() - center.min() < 8)
        assertTrue(center.all { it in 100..150 })
    }

    @Test fun filteringPreservesFlatColorsAndHandlesNarrowBitmaps() {
        for ((width, height) in listOf(1 to 1, 1 to 31, 31 to 1, 19 to 11)) {
            val source = IntArray(width * height) { 0xFF406080.toInt() }
            assertArrayEquals(source, LiquidOpticalSamplingPolicy.soften(source, width, height,
                width * 4, 2f))
        }
    }

    @Test fun cancelledBackgroundPreparationStopsWithoutMutatingItsInput() {
        val source = IntArray(64) { 0xFF304050.toInt() }
        val original = source.copyOf()
        Thread.currentThread().interrupt()
        try {
            val failure = runCatching { LiquidOpticalSamplingPolicy.soften(source, 8, 8, 32, 2f) }.exceptionOrNull()
            assertTrue(failure is InterruptedException)
            assertTrue(Thread.currentThread().isInterrupted)
            assertArrayEquals(original, source)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun topChromeHasNoHardFrameAndSelectedLensHasALighterEdgeThanItsTray() {
        assertEquals(0f, LiquidSurfaceEdgePolicy.alphaMultiplier(SurfaceRole.TOP_BAR), 0f)
        assertEquals(0f, LiquidSurfaceEdgePolicy.alphaMultiplier(SurfaceRole.WINDOW), 0f)
        assertTrue(LiquidSurfaceEdgePolicy.alphaMultiplier(SurfaceRole.SELECTED_ITEM) <
            LiquidSurfaceEdgePolicy.alphaMultiplier(SurfaceRole.FLOATING))
        assertTrue(SurfaceRole.entries.all { LiquidSurfaceEdgePolicy.alphaMultiplier(it) in 0f..0.55f })
    }
}
