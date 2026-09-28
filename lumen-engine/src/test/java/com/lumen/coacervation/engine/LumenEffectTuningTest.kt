package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.liquid.LiquidEffectProfile
import com.lumen.coacervation.engine.liquid.LiquidTokenResolver
import com.lumen.coacervation.engine.liquid.LiquidVisualTuningPolicy
import com.lumen.coacervation.engine.material.ModernMaterialPolicy
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenEffectTuningPolicy
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenEffectTuningTest {
    private fun allParameters() = listOf(false, true).flatMap { dark ->
        LiquidEffectProfile.entries.map { profile ->
            LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark), profile, dark)
        }
    }

    @Test fun defaultTuningLeavesEveryValueBitForBitUnchanged() {
        val tuning = LumenEffectTuning.DEFAULT
        allParameters().forEach { assertSame(it, LumenEffectTuningPolicy.applyTo(it, tuning)) }
        for (alpha in 0..255) assertEquals(alpha, LumenEffectTuningPolicy.edgeAlpha(alpha, tuning))
        for (density in listOf(.75f, 1f, 2.625f, 3.5f)) {
            assertEquals(density.coerceAtLeast(1f) * .65f, ModernMaterialPolicy.edgeStrokePx(density, 1f), 0f)
        }
        for (base in listOf(10f, 26.25f, 35f)) for (half in listOf(0f, 8f, 400f)) {
            assertEquals(base, LumenEffectTuningPolicy.bandWidth(base, tuning, half), 0f)
        }
        SurfaceRole.entries.forEach { role ->
            listOf(false, true).forEach { dark ->
                assertEquals(ModernMaterialPolicy.surface(role, dark), ModernMaterialPolicy.surface(role, dark, tuning))
            }
        }
    }

    @Test fun edgeWidthScalesTheRimAndKeepsTheSamplingPaddingBudget() {
        for (width in listOf(LumenEffectTuning.MIN_EDGE_WIDTH, .5f, 2f, LumenEffectTuning.MAX_EDGE_WIDTH)) {
            val tuning = LumenEffectTuning(edgeHighlightWidth = width)
            allParameters().forEach { base ->
                val tuned = LumenEffectTuningPolicy.applyTo(base, tuning)
                assertEquals(base.refractionHeightDp * width, tuned.refractionHeightDp, 1e-5f)
                assertEquals(base.highlightWidthDp * width, tuned.highlightWidthDp, 1e-5f)
                // 变薄同比收小位移，变厚不放大透镜。
                assertEquals(base.refractionAmountDp * minOf(width, 1f), tuned.refractionAmountDp, 1e-5f)
                // 与 LiquidTokenResolverTest 同一条采样预算：padding 必须包住 rim 与全部采样位移。
                assertTrue(tuned.effectPaddingDp >= tuned.refractionHeightDp + tuned.interiorDistortionDp +
                    tuned.scatteringRadiusDp + tuned.chromaticShiftDp)
                assertTrue(tuned.effectPaddingDp >= base.effectPaddingDp)
                assertEquals(base.specularStrength, tuned.specularStrength, 0f)
                assertEquals(base.highlightAlpha, tuned.highlightAlpha, 0f)
            }
        }
    }

    @Test fun widenedBandNeverCrossesTheSurfaceMidline() {
        val wide = LumenEffectTuning(edgeHighlightWidth = 4f)
        assertEquals(20f, LumenEffectTuningPolicy.bandWidth(10f, wide, 20f), 0f)
        assertEquals(40f, LumenEffectTuningPolicy.bandWidth(10f, wide, 400f), 0f)
        // 小表面上不比原值更窄：默认几何不因加宽而倒退。
        assertEquals(10f, LumenEffectTuningPolicy.bandWidth(10f, wide, 4f), 0f)
        assertEquals(5f, LumenEffectTuningPolicy.bandWidth(10f, LumenEffectTuning(edgeHighlightWidth = .5f), 4f), 0f)
    }

    @Test fun edgeIntensityScalesEveryHighlightTermAndZeroTurnsItOff() {
        val off = LumenEffectTuning(edgeHighlightIntensity = 0f)
        allParameters().forEach { base ->
            val tuned = LumenEffectTuningPolicy.applyTo(base, off)
            assertEquals(0f, tuned.specularStrength, 0f)
            assertEquals(0f, tuned.fresnelStrength, 0f)
            assertEquals(0f, tuned.highlightAlpha, 0f)
            assertEquals(base.refractionHeightDp, tuned.refractionHeightDp, 0f)
        }
        SurfaceRole.entries.forEach { role ->
            val style = ModernMaterialPolicy.surface(role, dark = false, tuning = off)
            assertEquals(0, style.upperEdgeAlpha)
            assertEquals(0, style.lowerEdgeAlpha)
            assertEquals(ModernMaterialPolicy.surface(role, false).tintAlpha, style.tintAlpha)
        }
        val max = LumenEffectTuning(edgeHighlightIntensity = LumenEffectTuning.MAX_EDGE_INTENSITY)
        assertEquals(255, LumenEffectTuningPolicy.edgeAlpha(140, max))
        allParameters().forEach { assertTrue(LumenEffectTuningPolicy.applyTo(it, max).highlightAlpha <= 1f) }
    }

    @Test fun constructorRejectsOutOfRangeAndClampedAcceptsAnything() {
        listOf(
            { LumenEffectTuning(edgeHighlightWidth = 0f) },
            { LumenEffectTuning(edgeHighlightWidth = 5f) },
            { LumenEffectTuning(edgeHighlightIntensity = -.1f) },
            { LumenEffectTuning(dragGlowIntensity = Float.NaN) },
            { LumenEffectTuning(dragGlowRadius = Float.POSITIVE_INFINITY) },
            { LumenEffectTuning(dragGlowRadius = .1f) },
            { LumenEffectTuning(dragDeformation = -.01f) },
            { LumenEffectTuning(dragDeformation = 2.01f) }
        ).forEach { build -> assertTrue(runCatching(build).exceptionOrNull() is IllegalArgumentException) }
        val clamped = LumenEffectTuning.clamped(
            edgeHighlightWidth = 100f, edgeHighlightIntensity = -3f,
            dragGlowIntensity = Float.NaN, dragGlowRadius = 0f, dragDeformation = 9f
        )
        assertEquals(LumenEffectTuning.MAX_EDGE_WIDTH, clamped.edgeHighlightWidth, 0f)
        assertEquals(0f, clamped.edgeHighlightIntensity, 0f)
        assertEquals(1f, clamped.dragGlowIntensity, 0f)
        assertEquals(LumenEffectTuning.MIN_DRAG_GLOW_RADIUS, clamped.dragGlowRadius, 0f)
        assertEquals(LumenEffectTuning.MAX_DRAG_DEFORMATION, clamped.dragDeformation, 0f)
        assertEquals(0f, LumenEffectTuning(dragDeformation = 0f).dragDeformation, 0f)
        assertEquals(LumenEffectTuning.DEFAULT, LumenEffectTuning.clamped())
    }

    /** 倍率必须真正接到两套材质与静态回退上，不能只停在公开类型。空白折叠后比对，换行不影响护栏。 */
    @Test fun tuningIsWiredIntoBothMaterialsAndTheFallback() {
        fun source(path: String) = SourceContract.read(path).replace(Regex("\\s+"), " ")
        val session = source("runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("LiquidActivityRenderer(activity, materialPalette, effectTuning)"))
        assertTrue(session.contains("FrostedMaterialRenderer(materialPalette, activity.resources.displayMetrics.density, effectTuning)"))
        val liquid = source("liquid/LiquidActivityRenderer.kt")
        assertTrue(liquid.contains("LumenEffectTuningPolicy.applyTo("))
        assertTrue(liquid.contains("LumenEffectTuningPolicy.bandWidth(OPTICAL_EDGE_BAND_DP * density, effectTuning,"))
        val frosted = source("material/FrostedMaterialRenderer.kt")
        assertTrue(frosted.contains("ModernMaterialPolicy.surface(role, dark, effectTuning), effectTuning.edgeHighlightWidth)"))
        assertTrue(frosted.contains("strokeWidth = ModernMaterialPolicy.edgeStrokePx(density, edgeWidthScale)"))
        val delegate = source("host/LumenActivityDelegate.kt")
        assertTrue(delegate.contains("ActivitySkinSession.create(activity, palette, effectTuning)"))
        assertTrue(delegate.contains("density, role, isDark, effectTuning)"))
        assertTrue(delegate.contains("SurfaceRole.FLOATING, isDark, effectTuning)"))
    }
}
