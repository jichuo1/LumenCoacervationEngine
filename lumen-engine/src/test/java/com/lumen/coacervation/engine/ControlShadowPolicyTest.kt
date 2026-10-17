package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.LumenControlShadowOptions
import com.lumen.coacervation.engine.material.ControlShadowPolicy
import com.lumen.coacervation.engine.material.ControlShadowPolicy.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlShadowPolicyTest {

    private val density = 4f
    private val blurPx = 10f * density
    private val typicalRadii = floatArrayOf(12f, 15f, 18f, 20f, 22f, 28f)
        .map { it * density }.toFloatArray()

    @Test fun optionsValidateRangesAndDisableByDefault() {
        val disabled = LumenControlShadowOptions()
        assertEquals(false, disabled.enabled)
        assertEquals(0.06f, disabled.contactOpacity, 0f)
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

    @Test fun metricsKeepBothLayersWithinBudget() {
        val cast = ControlShadowPolicy.textureMetrics(Layer.CAST, 112f, 64f, density)
        val contact = ControlShadowPolicy.textureMetrics(Layer.CONTACT, 112f, 0f, density)
        for (metrics in listOf(cast, contact)) {
            assertTrue(metrics.sidePx <= ControlShadowPolicy.MAX_LOGICAL_SIDE_PX)
            assertTrue(metrics.textureSidePx <= ControlShadowPolicy.MAX_TEXTURE_SIDE_PX)
            assertTrue(metrics.bytes <= ControlShadowPolicy.MAX_TEXTURE_BYTES)
            // 九宫格结构：逻辑 side = 2*pad + 2*corner + 直边芯；纹理侧按同一比例缩比
            assertEquals(2 * metrics.padPx + 2 * metrics.cornerPx +
                ControlShadowPolicy.CORE_STRAIGHT_PX, metrics.sidePx)
            assertEquals(2 * metrics.texturePadPx + 2 * metrics.textureCornerPx +
                ControlShadowPolicy.scalePx(ControlShadowPolicy.CORE_STRAIGHT_PX), metrics.textureSidePx)
        }
        // 宽幅环境项：模糊 64px 时 σ 受边长预算限制但仍保留可观的尾部
        assertTrue(cast.textureBlurPx >= 24)
        // 量化：参数微抖不产生新键
        assertEquals(
            ControlShadowPolicy.textureMetrics(Layer.CAST, 45.1f, 30.2f, density),
            ControlShadowPolicy.textureMetrics(Layer.CAST, 45.4f, 30.2f, density)
        )
        // 层不同则键不同，不能共用位图
        assertTrue(cast.key != contact.key)
        assertEquals(cast.textureSidePx * cast.textureSidePx * 4, cast.bytes)
    }

    @Test fun absurdRadiusStillProducesABoundedTexture() {
        val metrics = ControlShadowPolicy.textureMetrics(Layer.CAST, 10_000f, 10_000f, density)
        assertTrue(metrics.sidePx <= ControlShadowPolicy.MAX_LOGICAL_SIDE_PX)
        assertTrue(metrics.textureSidePx <= ControlShadowPolicy.MAX_TEXTURE_SIDE_PX)
        assertTrue(metrics.textureSidePx > 0)
    }

    @Test fun workingSetForTypicalControlRadiiFitsTheCacheBudget() {
        // 2026-10-17 真机回归（PMA110，密度 4.0）：材质页同屏出现 12/15/18/20/22/28dp 六种圆角。
        // 双层纹理（环境 + 接触带）合计必须落在 2 MiB 内，否则 LRU 循环抖动、每帧重建。
        val bytes = ControlShadowPolicy.workingSetBytes(typicalRadii, blurPx, density)
        assertTrue("working set $bytes exceeds ${ControlShadowPolicy.MAX_CACHE_BYTES}",
            bytes <= ControlShadowPolicy.MAX_CACHE_BYTES)
        // 保留 1.5 倍余量：宿主再增加几种圆角也不会立刻滑回循环抖动。
        assertTrue("working set $bytes leaves too little headroom",
            bytes * 3 / 2 <= ControlShadowPolicy.MAX_CACHE_BYTES)
    }

    @Test fun offsetBiasStrengthensTheBottomWithoutShiftingTheInnerEdge() {
        val centered = ControlShadowPolicy.textureMetrics(Layer.CAST, 60f, 40f, density, 0f)
        val down = ControlShadowPolicy.textureMetrics(Layer.CAST, 60f, 40f, density, 12f)
        assertEquals(0f, centered.offsetBias, 0f)
        assertTrue(down.offsetBias > 0f)
        // 内边界仍是峰值：增益在正中为 1，只向上/下分别减弱/加强。
        assertEquals(1f, ControlShadowPolicy.verticalGain(0.5f, down.offsetBias), 1e-6f)
        assertTrue(ControlShadowPolicy.verticalGain(0f, down.offsetBias) < 1f)
        assertTrue(ControlShadowPolicy.verticalGain(1f, down.offsetBias) > 1f)
        // 负偏移反向；键包含增益，不能与居中版本共用位图。
        val up = ControlShadowPolicy.textureMetrics(Layer.CAST, 60f, 40f, density, -12f)
        assertTrue(up.offsetBias < 0f)
        assertTrue(centered.key != down.key)
    }

    @Test fun roundedRectDistanceMatchesTheContour() {
        val half = 300f
        val radius = 60f
        assertEquals(-300f, ControlShadowPolicy.roundedRectDistance(0f, 0f, half, half, radius), 1e-3f)
        assertEquals(0f, ControlShadowPolicy.roundedRectDistance(half, 0f, half, half, radius), 1e-3f)
        assertEquals(60f, ControlShadowPolicy.roundedRectDistance(half + 60f, 0f, half, half, radius), 1e-3f)
        val diagonal = (half - radius) + radius / kotlin.math.sqrt(2f)
        assertTrue(ControlShadowPolicy.roundedRectDistance(diagonal, diagonal, half, half, radius) < 1e-3f)
    }

    @Test fun castProfileStartsAtTheContourAndDecaysWidely() {
        val sigma = 26f
        // 内侧不画（用户明确不要内缘暗边）
        assertEquals(0f, ControlShadowPolicy.castAlpha(0f, sigma), 1e-6f)
        assertEquals(0f, ControlShadowPolicy.castAlpha(-5f, sigma), 1e-6f)
        // 轮廓外立即达到峰值
        assertTrue(ControlShadowPolicy.castAlpha(0.5f, sigma) > 0.45f)
        var previous = 1f
        var distance = 0.5f
        while (distance < 120f) {
            val alpha = ControlShadowPolicy.castAlpha(distance, sigma)
            assertTrue("alpha increased at $distance", alpha <= previous + 1e-6f)
            previous = alpha
            distance += 1f
        }
        // 尾部在纹理边界处低于 1/255
        assertTrue(ControlShadowPolicy.castAlpha(65f, sigma) < 1f / 255f)
        // 零模糊不生成环境层
        assertEquals(0f, ControlShadowPolicy.castAlpha(10f, 0f), 1e-6f)
    }

    @Test fun contactBandPeaksJustOutsideTheContour() {
        val center = ControlShadowPolicy.CONTACT_CENTER_DP * density * ControlShadowPolicy.TEXTURE_SCALE
        val width = ControlShadowPolicy.CONTACT_WIDTH_DP * density * ControlShadowPolicy.TEXTURE_SCALE
        assertEquals(0f, ControlShadowPolicy.contactAlpha(0f, center, width), 1e-6f)
        assertEquals(0f, ControlShadowPolicy.contactAlpha(-3f, center, width), 1e-6f)
        val atEdge = ControlShadowPolicy.contactAlpha(0.5f, center, width)
        val atCenter = ControlShadowPolicy.contactAlpha(center, center, width)
        assertTrue(atCenter > atEdge)
        assertTrue(atEdge > 0.5f)
        assertTrue(ControlShadowPolicy.contactAlpha(
            ControlShadowPolicy.contactPadPx(density).toFloat(), center, width) < 1f / 255f)
    }

    @Test fun zeroBlurKeepsOnlyTheContactBand() {
        val cast = ControlShadowPolicy.textureMetrics(Layer.CAST, 60f, 0f, density)
        assertEquals(0, cast.textureBlurPx)
        val contact = ControlShadowPolicy.textureMetrics(Layer.CONTACT, 60f, 0f, density)
        val bytes = ControlShadowPolicy.workingSetBytes(floatArrayOf(60f), 0f, density)
        assertEquals(contact.bytes.toLong(), bytes)
    }

    @Test fun darkThemeStrengthensCastAndWeakensContact() {
        val light = ControlShadowPolicy.adaptAlphas(0.14f, 0.10f, dark = false, adaptToTheme = true)
        assertArrayEquals(floatArrayOf(0.14f, 0.10f), light, 1e-6f)
        val dark = ControlShadowPolicy.adaptAlphas(0.14f, 0.10f, dark = true, adaptToTheme = true)
        assertTrue(dark[0] > light[0])
        assertTrue(dark[1] < light[1])
        val off = ControlShadowPolicy.adaptAlphas(0.14f, 0.10f, dark = true, adaptToTheme = false)
        assertArrayEquals(light, off, 1e-6f)
        val capped = ControlShadowPolicy.adaptAlphas(0.6f, 0.4f, dark = true, adaptToTheme = true)
        assertEquals(0.6f, capped[0], 1e-6f)
    }
}
