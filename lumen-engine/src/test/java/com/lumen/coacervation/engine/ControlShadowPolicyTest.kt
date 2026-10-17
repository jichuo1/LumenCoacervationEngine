package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.LumenControlShadowOptions
import com.lumen.coacervation.engine.material.ControlShadowPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlShadowPolicyTest {

    @Test fun optionsValidateRangesAndDisableByDefault() {
        val disabled = LumenControlShadowOptions()
        assertEquals(false, disabled.enabled)
        assertThrows(IllegalArgumentException::class.java) { LumenControlShadowOptions(blurRadiusDp = 40f) }
        assertThrows(IllegalArgumentException::class.java) { LumenControlShadowOptions(offsetYDp = 20f) }
        assertThrows(IllegalArgumentException::class.java) { LumenControlShadowOptions(spreadDp = 12f) }
        assertThrows(IllegalArgumentException::class.java) { LumenControlShadowOptions(opacity = 0.9f) }
        assertThrows(IllegalArgumentException::class.java) { LumenControlShadowOptions(contactOpacity = 0.9f) }
        assertThrows(IllegalArgumentException::class.java) { LumenControlShadowOptions(opacity = Float.NaN) }
        // 边界值合法
        LumenControlShadowOptions(enabled = true, blurRadiusDp = 0f, offsetYDp = -8f, spreadDp = 8f,
            opacity = 0.6f, contactOpacity = 0f)
    }

    @Test fun textureMetricsStayWithinTheByteBudget() {
        // 最大模糊 + 最大圆角也不超 384px / 576KiB 单张上限
        val maxed = ControlShadowPolicy.textureMetrics(radiusPx = 10_000f, blurRadiusPx = 10_000f)
        assertTrue(maxed.sidePx <= ControlShadowPolicy.MAX_TEXTURE_SIDE_PX)
        assertTrue(maxed.bytes <= ControlShadowPolicy.MAX_TEXTURE_BYTES)
        assertTrue(maxed.blurPx <= ControlShadowPolicy.MAX_BLUR_TEXTURE_PX)
        // 量化：参数微抖不产生新键
        assertEquals(ControlShadowPolicy.textureMetrics(45.1f, 30.2f),
            ControlShadowPolicy.textureMetrics(45.4f, 30.2f))
        // 结构：side = 2*pad + 2*corner + 直边芯
        val m = ControlShadowPolicy.textureMetrics(45f, 30f)
        assertEquals(2 * m.padPx + 2 * m.cornerPx + ControlShadowPolicy.CORE_STRAIGHT_PX, m.sidePx)
        assertEquals(ControlShadowPolicy.padPx(m.blurPx), m.padPx)
    }

    @Test fun outsetAccountsForBlurSpreadAndDownwardOffset() {
        // 无模糊时只剩量化留白 2px + 正 spread + 正 offset
        assertEquals(2 + 4 + 6, ControlShadowPolicy.outsetPx(0f, 4f, 6f))
        // 向上的 offset 与负 spread 不占用外扩
        assertEquals(2, ControlShadowPolicy.outsetPx(0f, -4f, -3f))
        // 模糊先量化再换算留白：30 → 32，pad = ceil(32*2.5)+2 = 82
        assertEquals(ControlShadowPolicy.padPx(32), ControlShadowPolicy.outsetPx(30f, 0f, 0f))
        assertEquals(ControlShadowPolicy.padPx(28), ControlShadowPolicy.outsetPx(28f, 0f, 0f))
    }

    @Test fun darkThemeStrengthensCastAndWeakensContact() {
        val light = ControlShadowPolicy.adaptAlphas(0.14f, 0.10f, dark = false, adaptToTheme = true)
        assertArrayEquals(floatArrayOf(0.14f, 0.10f), light, 1e-6f)
        val dark = ControlShadowPolicy.adaptAlphas(0.14f, 0.10f, dark = true, adaptToTheme = true)
        assertTrue(dark[0] > light[0])
        assertTrue(dark[1] < light[1])
        // 关闭自适应或封顶处不再放大
        val off = ControlShadowPolicy.adaptAlphas(0.14f, 0.10f, dark = true, adaptToTheme = false)
        assertArrayEquals(light, off, 1e-6f)
        val capped = ControlShadowPolicy.adaptAlphas(0.6f, 0.4f, dark = true, adaptToTheme = true)
        assertEquals(0.6f, capped[0], 1e-6f)
    }

    @Test fun contactRingGeometryIsTwoConstantWidthStrokes() {
        val widths = ControlShadowPolicy.contactWidths(3f)
        assertTrue(widths[0] < widths[1])
        assertTrue(widths[0] >= 1f)
        val alphas = ControlShadowPolicy.contactAlphas(0.10f)
        assertEquals(0.10f, alphas[0], 1e-6f)
        assertEquals(0.04f, alphas[1], 1e-6f)
        // 低密度下仍有最小可读线宽
        val low = ControlShadowPolicy.contactWidths(1f)
        assertTrue(low[0] >= 1f && low[1] >= 2f)
    }
}
