package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidBackdropSizingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBackdropSizingPolicyTest {

    @Test
    fun `normal display uses quarter resolution`() {
        val size = LiquidBackdropSizingPolicy.resolve(1080, 2400)

        assertEquals(270, size.width)
        assertEquals(600, size.height)
        assertEquals(648_000L, size.byteCount)
    }

    @Test
    fun `fractional quarter dimensions round up`() {
        val size = LiquidBackdropSizingPolicy.resolve(101, 203)

        assertEquals(26, size.width)
        assertEquals(51, size.height)
    }

    @Test
    fun `very large display stays within two mebibytes`() {
        val size = LiquidBackdropSizingPolicy.resolve(16_000, 16_000)

        assertTrue(size.width > 0)
        assertTrue(size.height > 0)
        assertTrue(size.width.toLong() * size.height.toLong() <= 524_288L)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(size.byteCount >= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES * 9 / 10)
    }

    @Test
    fun `large wide display preserves aspect ratio while using the budget`() {
        val size = LiquidBackdropSizingPolicy.resolve(16_000, 4_000)

        assertEquals(4.0, size.width.toDouble() / size.height.toDouble(), 0.02)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(size.byteCount >= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES * 9 / 10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero dimension is rejected`() {
        LiquidBackdropSizingPolicy.resolve(0, 100)
    }

    @Test fun `phone preview uses actual view pixels rather than a fixed canvas`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(1080, 420)
        assertEquals(1080, size.width)
        assertEquals(420, size.height)
        assertTrue(size.width > 640)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
    }

    @Test fun `small preview is not upscaled`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(320, 180)
        assertEquals(320, size.width)
        assertEquals(180, size.height)
    }

    @Test fun `preview at the budget stays at native resolution`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(1024, 512)
        assertEquals(1024, size.width)
        assertEquals(512, size.height)
        assertEquals(LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES, size.byteCount)
    }

    @Test fun `tablet preview scales within existing budget without distortion`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(2560, 900)
        assertEquals(2560.0 / 900, size.width.toDouble() / size.height, 0.02)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        assertTrue(size.byteCount >= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES * 9 / 10)
    }

    @Test fun `portrait preview preserves aspect ratio`() {
        val size = LiquidBackdropSizingPolicy.resolvePreview(900, 2560)
        assertEquals(900.0 / 2560, size.width.toDouble() / size.height, 0.01)
        assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
    }

    @Test fun `extreme aspect ratios and dimensions have bounded cost`() {
        for ((width, height) in listOf(Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE,
            Int.MAX_VALUE to Int.MAX_VALUE)) {
            val size = LiquidBackdropSizingPolicy.resolvePreview(width, height)
            assertTrue(size.width > 0 && size.height > 0)
            assertTrue(size.byteCount <= LiquidBackdropSizingPolicy.MAX_BUFFER_BYTES)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unmeasured preview is rejected`() {
        LiquidBackdropSizingPolicy.resolvePreview(0, 420)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative preview height is rejected`() {
        LiquidBackdropSizingPolicy.resolvePreview(1080, -1)
    }
}
