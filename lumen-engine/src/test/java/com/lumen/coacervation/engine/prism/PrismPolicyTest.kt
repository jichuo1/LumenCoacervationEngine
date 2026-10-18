package com.lumen.coacervation.engine.prism

import com.lumen.coacervation.engine.host.LumenPrismBlurOptions
import com.lumen.coacervation.engine.host.LumenPrismFallbackReason
import com.lumen.coacervation.engine.host.LumenPrismHighlightOptions
import com.lumen.coacervation.engine.host.LumenPrismInnerShadowOptions
import com.lumen.coacervation.engine.host.LumenPrismLensOptions
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenPrismPressOptions
import com.lumen.coacervation.engine.host.LumenPrismShadowOptions
import com.lumen.coacervation.engine.host.LumenPrismSurfaceOptions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PrismPolicyTest {
    private val defaults = LumenPrismOptions()

    @Test fun shadeDefaultsMatchTheReferenceBottomBar() {
        assertEquals(0x66FAFAFA, PrismPolicy.tint(defaults, dark = false))
        assertEquals(0x66121212, PrismPolicy.tint(defaults, dark = true))
        assertEquals(0xFFFAFAFA.toInt(), PrismPolicy.fallback(defaults, dark = false))
        assertEquals(0xFF121212.toInt(), PrismPolicy.fallback(defaults, dark = true))
    }

    @Test fun customTintKeepsItsHueAndFallbackOpacityMatters() {
        val options = defaults.copy(surface = LumenPrismSurfaceOptions(tintColor = 0x802A5EA8.toInt(), tintOpacity = 0.25f))
        for (dark in listOf(false, true)) assertEquals(0x402A5EA8, PrismPolicy.tint(options, dark))
        // Review regression: fallbackOpacity changes the fallback when the tint differs from the neutral base.
        val full = PrismPolicy.fallback(options.copy(surface = options.surface.copy(fallbackOpacity = 1f)), dark = false)
        val third = PrismPolicy.fallback(options.copy(surface = options.surface.copy(fallbackOpacity = 0.3f)), dark = false)
        val none = PrismPolicy.fallback(options.copy(surface = options.surface.copy(fallbackOpacity = 0f)), dark = false)
        assertEquals(0xFF2A5EA8.toInt(), full)
        assertEquals(0xFFFAFAFA.toInt(), none)
        assertEquals(0xFF, third ushr 24)
        assertEquals(((0xFA + (0x2A - 0xFA) * 0.3f) + 0.5f).toInt(), (third shr 16) and 0xFF)
        // An explicit fallback colour wins, opaque.
        assertEquals(0xFF102030.toInt(), PrismPolicy.fallback(options.copy(surface = options.surface.copy(fallbackColor = 0x10102030)), dark = true))
    }

    @Test fun saturationMatrixIsIdentityAtOneAndMatchesVibrancyAtOneAndAHalf() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        assertArrayEquals(identity, PrismPolicy.saturationMatrix(1f), 1e-6f)
        val vibrancy = PrismPolicy.saturationMatrix(1.5f)
        assertArrayEquals(floatArrayOf(1.3935f, -0.3575f, -0.036f), vibrancy.copyOfRange(0, 3), 1e-4f)
        assertArrayEquals(floatArrayOf(-0.1065f, 1.1425f, -0.036f), vibrancy.copyOfRange(5, 8), 1e-4f)
        assertArrayEquals(floatArrayOf(-0.1065f, -0.3575f, 1.464f), vibrancy.copyOfRange(10, 13), 1e-4f)
        // Each row preserves grey: luminance-weighted rows sum to one.
        for (row in 0 until 3) assertEquals(1f, vibrancy[row * 5] + vibrancy[row * 5 + 1] + vibrancy[row * 5 + 2], 1e-5f)
    }

    @Test fun recipeDropsEveryDisabledLayer() {
        assertEquals(PrismRecipe(8f, 1.5f, 24f, 24f, 0f, 0f), PrismPolicy.recipe(defaults))
        val noBlur = PrismPolicy.recipe(defaults.copy(blur = LumenPrismBlurOptions(enabled = false)))
        // Saturation is its own layer: switching the blur off keeps it.
        assertEquals(0f, noBlur.blurRadiusDp); assertEquals(1.5f, noBlur.saturation); assertTrue(noBlur.lensEnabled)
        val noLens = PrismPolicy.recipe(defaults.copy(lens = LumenPrismLensOptions(enabled = false, depthEffect = 1f, dispersion = 1f)))
        assertFalse(noLens.lensEnabled); assertEquals(0f, noLens.depthEffect); assertEquals(0f, noLens.dispersion)
        assertFalse(PrismPolicy.recipe(defaults.copy(lens = LumenPrismLensOptions(refractionAmountDp = 0f))).lensEnabled)
    }

    @Test fun refractionPullsInwardOnlyInsideTheBevel() {
        assertEquals(0f, PrismPolicy.refractionOffset(24f, 24f, 24f), 0f)
        assertEquals(0f, PrismPolicy.refractionOffset(30f, 24f, 24f), 0f)
        assertEquals(-24f, PrismPolicy.refractionOffset(0f, 24f, 24f), 1e-5f)
        val mid = PrismPolicy.refractionOffset(12f, 24f, 24f)
        assertEquals(-(1f - sqrt(1f - 0.25f)) * 24f, mid, 1e-5f)
        assertEquals(0f, PrismPolicy.refractionOffset(0f, 0f, 24f), 0f)
    }

    @Test fun paddingCoversOnlyTheBlur() {
        assertEquals(2, PrismPolicy.paddingPx(PrismRecipe(0f, 1f, 24f, 64f, 1f, 1f), 3f))
        assertEquals(50, PrismPolicy.paddingPx(PrismRecipe(8f, 1.5f, 0f, 0f, 0f, 0f), 3f))
    }

    @Test fun radiusIsCapsuleByDefaultAndNeverExceedsHalfTheShortSide() {
        assertEquals(96f, PrismPolicy.radiusPx(defaults, 3f, 900f, 192f), 0f)
        assertEquals(30f, PrismPolicy.radiusPx(defaults.copy(cornerRadiusDp = 10f), 3f, 900f, 192f), 0f)
        assertEquals(96f, PrismPolicy.radiusPx(defaults.copy(cornerRadiusDp = 100f), 3f, 900f, 192f), 0f)
        assertEquals(128f, PrismPolicy.cornerRadiusDp(defaults), 0f)
        // Capsules cap at 128dp everywhere, matching the radius handed to the session's GPU node.
        assertEquals(384f, PrismPolicy.radiusPx(defaults, 3f, 2000f, 1000f), 0f)
        // A vertical capsule uses the short side.
        assertEquals(60f, PrismPolicy.radiusPx(defaults, 3f, 120f, 600f), 0f)
    }

    @Test fun fallbackOrderAndBackdropRelease() {
        assertEquals(LumenPrismFallbackReason.NONE, PrismPolicy.policyFallback(defaults, lowRamDevice = false, powerSave = true))
        assertEquals(LumenPrismFallbackReason.DISABLED, PrismPolicy.policyFallback(defaults.copy(enabled = false, reduceTransparency = true), true, true))
        assertEquals(LumenPrismFallbackReason.REDUCE_TRANSPARENCY, PrismPolicy.policyFallback(defaults.copy(reduceTransparency = true), true, true))
        assertEquals(LumenPrismFallbackReason.LOW_RAM_DEVICE, PrismPolicy.policyFallback(defaults, lowRamDevice = true, powerSave = true))
        assertEquals(LumenPrismFallbackReason.NONE, PrismPolicy.policyFallback(defaults.copy(fallbackOnLowRamDevice = false), true, false))
        assertEquals(LumenPrismFallbackReason.POWER_SAVE, PrismPolicy.policyFallback(defaults.copy(fallbackOnPowerSave = true), false, true))
        assertFalse(PrismPolicy.releasesBackdrop(LumenPrismFallbackReason.NONE))
        assertFalse(PrismPolicy.releasesBackdrop(LumenPrismFallbackReason.NO_BACKDROP))
        assertFalse(PrismPolicy.releasesBackdrop(LumenPrismFallbackReason.BACKDROP_UNAVAILABLE))
        assertTrue(PrismPolicy.releasesBackdrop(LumenPrismFallbackReason.DISABLED))
        assertTrue(PrismPolicy.releasesBackdrop(LumenPrismFallbackReason.POWER_SAVE))
    }

    @Test fun highlightLightsBothDiagonalEdgesAndFallsOffBetween() {
        val light = FloatArray(2); val n = FloatArray(2)
        PrismPolicy.lightDirection(45f, light)
        assertEquals(sqrt(0.5f), light[0], 1e-5f); assertEquals(sqrt(0.5f), light[1], 1e-5f)
        // Corner normals on a 200x100 rect with radius 50 (capsule ends).
        PrismPolicy.gradient(90f, 40f, 100f, 50f, 50f, n)
        val bottomRight = PrismPolicy.highlightIntensity(n[0], n[1], light[0], light[1], 1f)
        PrismPolicy.gradient(-90f, -40f, 100f, 50f, 50f, n)
        val topLeft = PrismPolicy.highlightIntensity(n[0], n[1], light[0], light[1], 1f)
        PrismPolicy.gradient(90f, -40f, 100f, 50f, 50f, n)
        val topRight = PrismPolicy.highlightIntensity(n[0], n[1], light[0], light[1], 1f)
        assertEquals(bottomRight, topLeft, 1e-5f)
        assertTrue(bottomRight > 0.9f)
        assertTrue(topRight < 0.2f)
        assertEquals(1f, PrismPolicy.highlightIntensity(0f, 1f, 1f, 0f, 0f), 0f)
        // Straight edge: normal is axis-aligned and never NaN, also on the centre line.
        PrismPolicy.gradient(0f, -50f, 100f, 50f, 50f, n)
        assertEquals(0f, n[0], 0f); assertEquals(-1f, n[1], 0f)
        PrismPolicy.gradient(0f, 0f, 100f, 50f, 50f, n)
        assertFalse(n[0].isNaN() || n[1].isNaN())
    }

    @Test fun optionsRejectOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) { LumenPrismOptions(cornerRadiusDp = 129f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismBlurOptions(radiusDp = 49f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismBlurOptions(saturation = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismLensOptions(dispersion = 1.1f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismLensOptions(refractionHeightDp = -1f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismSurfaceOptions(tintOpacity = 2f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismHighlightOptions(falloff = 9f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismInnerShadowOptions(opacity = 0.7f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismPressOptions(glowOpacity = 0.6f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismShadowOptions(opacity = 0.6f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismShadowOptions(radiusDp = 49f) }
        // Defaults are the documented reference recipe (Shadow.Default: 24dp, 4dp down, 10% black).
        assertEquals(LumenPrismShadowOptions(true, 24f, 4f, 0.10f), defaults.shadow)
        assertFalse(defaults.innerShadow.enabled)
    }
}
