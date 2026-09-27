package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.material.LensRefractionPolicy
import com.lumen.coacervation.engine.material.ModernMaterialPolicy
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.*
import org.junit.Test

class LensRefractionPolicyTest {
    @Test fun sampleScaleKeepsTheBitmapBudgetBounded() {
        for ((w, h) in listOf(1 to 1, 1080 to 240, 1440 to 320, 3840 to 2160, 20000 to 20000)) {
            val scale = LensRefractionPolicy.sampleScale(w, h)
            assertTrue(scale >= LensRefractionPolicy.MIN_SCALE)
            val pixels = (w / scale).toLong().coerceAtLeast(1) * (h / scale).coerceAtLeast(1)
            assertTrue("$w x $h → scale $scale", pixels <= LensRefractionPolicy.MAX_SAMPLE_PIXELS)
        }
        assertEquals(LensRefractionPolicy.MIN_SCALE, LensRefractionPolicy.sampleScale(0, 10))
    }

    @Test fun lensIsOddMonotonicAndContinuous() {
        for ((gain, push) in listOf(
            LensRefractionPolicy.CENTER_GAIN_X to LensRefractionPolicy.RIM_PUSH_X,
            LensRefractionPolicy.CENTER_GAIN_Y to LensRefractionPolicy.RIM_PUSH_Y
        )) {
            assertEquals(0f, LensRefractionPolicy.lens(0f, gain, push), 1e-6f)
            var previous = LensRefractionPolicy.lens(-1f, gain, push)
            var u = -1f
            while (u < 1f) {
                u += 1f / 512f
                val value = LensRefractionPolicy.lens(u, gain, push)
                assertTrue(value.isFinite())
                assertTrue("单调 @ $u", value >= previous - 1e-6f)
                assertTrue("连续 @ $u", value - previous < 0.02f)
                assertEquals("奇函数 @ $u", -value, LensRefractionPolicy.lens(-u, gain, push), 1e-5f)
                previous = value
            }
            // 中心放大：内区比例 < 1；外沿推出：边缘越出 1。
            assertTrue(LensRefractionPolicy.lens(0.3f, gain, push) < 0.3f)
            assertTrue(LensRefractionPolicy.lens(1f, gain, push) > 1f)
            assertEquals(LensRefractionPolicy.lens(1f, gain, push), LensRefractionPolicy.lens(7f, gain, push), 0f)
        }
    }

    @Test fun remapPreservesConstantImagesAndStaysInsideTheSource() {
        val sw = 40
        val sh = 16
        val margin = 4
        val source = IntArray(sw * sh) { 0x80402010.toInt() }
        val out = IntArray(90 * 30)
        LensRefractionPolicy.remap(source, sw, sh, margin, out, 90, 30)
        assertTrue(out.all { it == 0x80402010.toInt() })

        val gradient = IntArray(sw * sh) { i -> 0xFF000000.toInt() or ((i % sw) * 6) }
        LensRefractionPolicy.remap(gradient, sw, sh, margin, out, 90, 30)
        for (i in 0 until 90) {
            val a = out[i] ushr 24
            val b = out[i] and 0xFF
            assertEquals(0xFF, a)
            assertTrue(b in 0..(sw - 1) * 6)
        }
        assertTrue(runCatching { LensRefractionPolicy.remap(source, sw, sh, margin, out, 0, 30) }.isFailure)
    }

    @Test fun premultiplyRoundTripsOpaqueAndDropsFullyTransparentColor() {
        val pixels = intArrayOf(0xFF10A0F0.toInt(), 0x00FFFFFF, 0x80FF0000.toInt())
        LensRefractionPolicy.premultiply(pixels)
        assertEquals(0xFF10A0F0.toInt(), pixels[0])
        assertEquals(0, pixels[1])
        LensRefractionPolicy.unpremultiply(pixels)
        assertEquals(0xFF10A0F0.toInt(), pixels[0])
        assertEquals(0, pixels[1])
        assertEquals(0x80, pixels[2] ushr 24)
        assertTrue((pixels[2] shr 16 and 0xFF) >= 0xFE)
    }

    @Test fun illuminateLiftsPremultipliedChannelsAndKeepsAlpha() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0x80FF0000.toInt(), 0x00000000, 0xFFF0F0F0.toInt())
        LensRefractionPolicy.illuminate(pixels)
        // 提亮只动预乘 RGB：alpha 通道原样保留，全透明像素不产出颜色。
        assertEquals(0xFF, pixels[0] ushr 24)
        assertTrue((pixels[0] ushr 16 and 255) > 0x10)
        assertTrue((pixels[0] and 255) > 0x30)
        assertEquals(0x80, pixels[1] ushr 24)
        assertEquals(0, pixels[2])
        // 接近满亮的像素被钳到 255，不溢出回绕。
        assertEquals(0xFFFFFFFF.toInt(), pixels[3])
    }

    @Test fun onlyFloatingTopBarAndModalUseTheLiveBackdrop() {
        for (dark in listOf(false, true)) {
            for (role in SurfaceRole.values()) {
                val style = ModernMaterialPolicy.surface(role, dark)
                val expected = role == SurfaceRole.FLOATING || role == SurfaceRole.TOP_BAR ||
                    role == SurfaceRole.MODAL
                assertEquals(role.name, expected, style.live)
            }
        }
    }
}
