package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidSurfaceAlphaPolicy
import com.lumen.coacervation.engine.liquid.LiquidEffectProfile
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidTokenResolverTest {

    @Test
    fun `resolver preserves visual tuning mapping`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)
            val parameters = LiquidTokenResolver.resolve(tuning)

            assertEquals(tuning.cardGlassAlpha, parameters.surfaceAlpha, 0f)
            assertEquals(tuning.modalGlassAlpha, parameters.modalSurfaceAlpha, 0f)
            assertEquals(tuning.motionGlassAlpha, parameters.motionSurfaceAlpha, 0f)
            assertEquals(tuning.cardFallbackAlpha, parameters.fallbackSurfaceAlpha, 0f)
            assertEquals(tuning.modalFallbackAlpha, parameters.fallbackModalSurfaceAlpha, 0f)
            assertEquals(
                tuning.motionFallbackAlpha,
                parameters.fallbackMotionSurfaceAlpha,
                0f
            )
            assertEquals(tuning.saturation, parameters.saturation, 0f)
            assertEquals(0f, parameters.scatteringStrength, 0f)
            assertTrue(parameters.highlightWidthDp <= 0.5f)
            // 标准档使用共享预滤波背景与细折射边，额外定向光学仍只在实时档启用。
            assertEquals(0f, parameters.specularStrength, 0f)
            assertEquals(0f, parameters.fresnelStrength, 0f)
            assertEquals(0f, parameters.causticLuminanceGain, 0f)
            assertEquals(0f, parameters.innerShadowStrength, 0f)
        }
    }

    @Test
    fun `surface role and backend matrix selects the intended alpha`() {
        val parameters = LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark = true))

        assertEquals(
            parameters.surfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.CARD, false, parameters),
            0f
        )
        assertEquals(
            parameters.modalSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MODAL, false, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.CARD, true, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackModalSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MODAL, true, parameters),
            0f
        )
        assertEquals(
            parameters.motionSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MOTION_SURFACE, false, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackMotionSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MOTION_SURFACE, true, parameters),
            0f
        )
    }

    @Test
    fun `floating surfaces composite over real content while solids stay opaque`() {
        // "对下取色"（2026-09-21）：底部导航胶囊/选中滑块这类浮在滚动内容上的表面，
        // 玻璃层必须以部分 alpha 输出，真实下层内容才能透入合成——只折射合成底图
        // 永远拿不到底下的列表内容。呼出面板同理透入 scrim 压暗的底页；
        // 卡片/顶栏下层就是窗口底色，保持全不透明。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING) < 1f)
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.SELECTED_ITEM) < 1f)
        // 呼出面板与浮动条同一套做法：透出 scrim 压暗后的底页，读作通透玻璃。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.MODAL) < 1f)
        assertEquals(1f, LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.CARD), 0f)
        assertEquals(1f, LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.TOP_BAR), 0f)
        // 透出量必须够明显——只留一层近乎不可见的膜不算"通透"。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING) <= 0.7f)
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.SELECTED_ITEM) <= 0.7f)
        // 模态层透入的是被 scrim 压暗的内容，通透性可以比浮动条更收一些，
        // 但要留下可读出的下层映射。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.MODAL) <= 0.75f)
        // 2026-09-23 悬浮栏可读性改造：浮动条直透从 58% 降到 35%（0.42→0.65），栏里不再叠一层
        // 锐利文字；"对下取色"改由滚动边缘溶解 + 自适应补偿承担。原先"模态层比浮动条更不透"
        // 的次序约束随之撤销，改为给浮动条设可读性下限。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING) >= 0.6f)
    }

    @Test
    fun `realtime profile keeps optics visible while preserving readable fallback`() {
        val tuning = LiquidVisualTuningPolicy.resolve(dark = true)
        val standard = LiquidTokenResolver.resolve(tuning)
        val realtime = LiquidTokenResolver.resolve(
            tuning,
            LiquidEffectProfile.REALTIME_CAPTURE
        )

        assertTrue(realtime.refractionHeightDp > standard.refractionHeightDp)
        assertTrue(realtime.refractionAmountDp > standard.refractionAmountDp)
        assertTrue(realtime.interiorDistortionDp > 0f)
        // 色散限域在 rim 带内（shader 按 edgeWeight 缩放、<0.02px 跳过取样）后重新开启：
        // 亚像素量级，整块彩边由限域排除；上界钉死防止调参漂移。标准档仍为 0。
        assertTrue(realtime.chromaticShiftDp > 0f)
        assertTrue(realtime.chromaticShiftDp <= 0.75f)
        assertEquals(0f, standard.chromaticShiftDp, 0f)
        // 抖动只给实时档；标准档为 0 时 shader 整条分支被 uniform 门掉。
        assertTrue(realtime.ditherAmplitude > 0f)
        assertTrue(realtime.ditherAmplitude <= 2f / 255f)
        assertEquals(0f, standard.ditherAmplitude, 0f)
        // 实时档采真实内容，微提饱和；标准档采已调好的光学底图，不动。
        assertTrue(realtime.saturation > standard.saturation)
        assertTrue(realtime.saturation <= 1.06f)
        assertEquals(tuning.saturation, standard.saturation, 0f)
        assertTrue(realtime.scatteringRadiusDp > 0f)
        assertTrue(realtime.scatteringStrength > 0f)
        assertTrue(realtime.surfaceAlpha < standard.surfaceAlpha)
        assertTrue(realtime.highlightWidthDp <= standard.highlightWidthDp)
        assertTrue(realtime.highlightAlpha < standard.highlightAlpha)
        assertEquals(standard.fallbackSurfaceAlpha, realtime.fallbackSurfaceAlpha, 0f)
    }

    @Test
    fun `realtime profile enables directional optics and standard profile does not`() {
        val tuning = LiquidVisualTuningPolicy.resolve(dark = true)
        val standard = LiquidTokenResolver.resolve(tuning)
        val realtime = LiquidTokenResolver.resolve(
            tuning,
            LiquidEffectProfile.REALTIME_CAPTURE
        )

        assertTrue(realtime.specularStrength > 0f)
        assertTrue(realtime.fresnelStrength > 0f)
        assertTrue(realtime.causticLuminanceGain > 0f)
        assertTrue(realtime.innerShadowStrength > 0f)
        // 边缘亮度由 shader 的定向高光承担后，均匀白描边必须明显让位。
        assertTrue(realtime.highlightAlpha <= 0.22f)
        assertTrue(realtime.specularStrength <= 0.20f)
        assertTrue(realtime.fresnelStrength <= 0.08f)
        assertTrue(realtime.causticLuminanceGain <= 0.55f)
        // 光源方位角约定 L = (cos θ, sin θ)、y 轴向下；-145° 指向左上方。
        assertEquals(-145f, realtime.highlightAngleDegrees, 0f)
        assertEquals(standard.highlightAngleDegrees, realtime.highlightAngleDegrees, 0f)
    }

    @Test
    fun `diffused surfaces retain a narrow lens without wide bright or recessed bands`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)
            LiquidEffectProfile.entries.forEach { profile ->
                val parameters = LiquidTokenResolver.resolve(tuning, profile)
                assertTrue(parameters.refractionHeightDp in 1f..12f)
                assertTrue(parameters.refractionAmountDp in 1f..7f)
                assertTrue(parameters.depthEffect <= .16f)
                assertTrue(parameters.interiorDistortionDp <= 1.75f)
                assertTrue(parameters.specularStrength <= .06f)
                assertTrue(parameters.fresnelStrength <= .025f)
                assertTrue(parameters.innerShadowStrength <= .018f)
                // 采样触达上界要把色散位移一并算进 padding 预算，否则 rim 色散会采到
                // effect 区之外。
                assertTrue(parameters.effectPaddingDp >= parameters.refractionHeightDp +
                    parameters.interiorDistortionDp + parameters.scatteringRadiusDp +
                    parameters.chromaticShiftDp)
                assertTrue(parameters.saturation >= tuning.saturation)
                assertTrue(parameters.saturation <= 1.06f)
            }
        }
    }

    /** 2026-09-24：浅色玻璃不画内阴影（rim 中段压暗在近白表面上读作"向里发灰"），深色保留。 */
    @Test fun lightPaletteDropsTheInnerShadow() {
        val tuning = LiquidVisualTuningPolicy.resolve(dark = false)
        val light = LiquidTokenResolver.resolve(tuning, LiquidEffectProfile.REALTIME_CAPTURE, dark = false)
        val dark = LiquidTokenResolver.resolve(tuning, LiquidEffectProfile.REALTIME_CAPTURE, dark = true)
        assertEquals(0f, light.innerShadowStrength, 0f)
        assertTrue(dark.innerShadowStrength > 0f)
        assertEquals(light.copy(innerShadowStrength = dark.innerShadowStrength), dark)
    }
}
