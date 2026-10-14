package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.fluent.AcrylicAvailability
import com.lumen.coacervation.engine.fluent.FluentAcrylicPolicy
import com.lumen.coacervation.engine.fluent.FluentElevationPolicy
import com.lumen.coacervation.engine.fluent.FluentTransition
import com.lumen.coacervation.engine.host.LumenAcrylicFallbackReason
import com.lumen.coacervation.engine.host.LumenAcrylicKind
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import com.lumen.coacervation.engine.host.LumenFluentControlOptions
import com.lumen.coacervation.engine.host.LumenFluentElevation
import com.lumen.coacervation.engine.host.LumenFluentElevationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected values are worked by hand from microsoft-ui-xaml AcrylicBrush.cpp and its theme resources. */
class FluentAcrylicPolicyTest {
    private val accent = 0xFF0067C0.toInt()

    @Test fun themeDefaultsMatchInAppAcrylicBrushes() {
        val light = FluentAcrylicPolicy.resolve(LumenAcrylicOptions(), dark = false, accent = accent)
        assertEquals(0x00FCFCFC, light.tint)                 // TintOpacity 0
        assertEquals(0xD9FCFCFC.toInt(), light.luminosity)   // 0.85 → 217
        assertEquals(0xFFF9F9F9.toInt(), light.fallback)
        val dark = FluentAcrylicPolicy.resolve(LumenAcrylicOptions(), dark = true, accent = accent)
        assertEquals(0x262C2C2C, dark.tint)                  // 0.15 → 38, no modifier when luminosity is given
        assertEquals(0xF52C2C2C.toInt(), dark.luminosity)    // 0.96 → 245
        assertEquals(0xFF2C2C2C.toInt(), dark.fallback)
        assertEquals(5, dark.noiseAlpha)                     // 2% noise
    }

    @Test fun baseAndAccentKindsUseTheirOwnBrushes() {
        val base = FluentAcrylicPolicy.resolve(LumenAcrylicOptions(kind = LumenAcrylicKind.BASE), dark = true, accent = accent)
        assertEquals(0x80202020.toInt(), base.tint)
        assertEquals(0xFF1C1C1C.toInt(), base.fallback)
        val accentLight = FluentAcrylicPolicy.resolve(LumenAcrylicOptions(kind = LumenAcrylicKind.ACCENT), dark = false, accent = accent)
        assertEquals(0xCC0067C0.toInt(), accentLight.tint)
        assertEquals(0xE60067C0.toInt(), accentLight.luminosity)
        assertEquals(0xFF0067C0.toInt(), accentLight.fallback)
    }

    @Test fun tintOpacityModifierCompressesByValueAndRelaxesWithSaturation() {
        assertEquals(0.45, FluentAcrylicPolicy.tintOpacityModifier(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(0.85, FluentAcrylicPolicy.tintOpacityModifier(0xFF000000.toInt()), 1e-9)
        assertEquals(0.90, FluentAcrylicPolicy.tintOpacityModifier(0xFF808080.toInt()), 0.003)
        assertEquals(0.90, FluentAcrylicPolicy.tintOpacityModifier(0xFFFF0000.toInt()), 1e-9)
    }

    @Test fun derivedLuminosityClampsValueAndMapsOpacity() {
        assertEquals(0xFFF6F6F6.toInt(), FluentAcrylicPolicy.luminosityColor(0xFFFFFFFF.toInt(), null))
        assertEquals(0x26202020, FluentAcrylicPolicy.luminosityColor(0x00000000, null))
        assertEquals(0x80123456.toInt(), FluentAcrylicPolicy.luminosityColor(0xFF123456.toInt(), 0.5f))
    }

    @Test fun customTintWithoutLuminosityUsesModifierAndDerivedLuminosity() {
        val recipe = FluentAcrylicPolicy.resolve(LumenAcrylicOptions(tintColor = 0xFFFFFFFF.toInt(), tintOpacity = 1f),
            dark = false, accent = accent)
        assertEquals(0x73FFFFFF, recipe.tint)                // 255 × 1 × 0.45 = 114.75 → 115
        assertEquals(0xFFF6F6F6.toInt(), recipe.luminosity)
        val explicit = FluentAcrylicPolicy.resolve(LumenAcrylicOptions(tintColor = 0xFFFFFFFF.toInt(), tintOpacity = 1f,
            luminosityOpacity = 0.5f), dark = false, accent = accent)
        assertEquals(0xFFFFFFFF.toInt(), explicit.tint)
        assertEquals(0x80FFFFFF.toInt(), explicit.luminosity)
    }

    @Test fun fallbackColourIsAlwaysOpaque() {
        val recipe = FluentAcrylicPolicy.resolve(LumenAcrylicOptions(fallbackColor = 0x40112233), dark = false, accent = accent)
        assertEquals(0xFF112233.toInt(), recipe.fallback)
    }

    @Test fun defaultBlurIsTheClosestAllowedRadiusToFluentSigma() {
        val radius = FluentAcrylicPolicy.blurRadiusForSigma(FluentAcrylicPolicy.FLUENT_BLUR_SIGMA)
        assertEquals(51.09f, radius, 0.01f)
        assertEquals(minOf(48f, radius), LumenAcrylicOptions().blurRadiusDp, 0f)
        assertEquals(0f, FluentAcrylicPolicy.blurRadiusForSigma(0.5f), 0f)
    }

    @Test fun approximateLuminosityStaysBelowTheExactAlpha() {
        for (alpha in listOf(0, 38, 217, 245, 255)) {
            val approximate = FluentAcrylicPolicy.approximateLuminosityAlpha(alpha)
            assertTrue(approximate in 0..alpha)
        }
    }

    @Test fun crossfadeEaseIsMonotonicAndPinned() {
        assertEquals(0f, FluentAcrylicPolicy.crossfadeEase(0f), 0f)
        assertEquals(1f, FluentAcrylicPolicy.crossfadeEase(1f), 0f)
        var previous = 0f
        for (i in 1..100) {
            val value = FluentAcrylicPolicy.crossfadeEase(i / 100f)
            assertTrue(value >= previous - 1e-5f)
            previous = value
        }
        // Brute-force reference: sample the curve densely and read y at the closest x.
        fun reference(x: Float): Float {
            var best = 0f
            var distance = Float.MAX_VALUE
            for (step in 0..20_000) {
                val u = step / 20_000f
                val v = 1f - u
                val bx = 3f * v * v * u * 0.5f + u * u * u
                val by = 3f * v * u * u * 0.9f + u * u * u
                val d = kotlin.math.abs(bx - x)
                if (d < distance) { distance = d; best = by }
            }
            return best
        }
        for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) assertEquals(reference(x), FluentAcrylicPolicy.crossfadeEase(x), 2e-3f)
    }

    @Test fun policyFallbackPrecedenceAndBackdropRelease() {
        fun reason(enabled: Boolean = true, reduce: Boolean = false, lowRam: Boolean = false, power: Boolean = false,
            inactive: Boolean = false, backdrop: Boolean = true) =
            FluentAcrylicPolicy.policyFallback(enabled, reduce, lowRam, power, inactive, backdrop)
        assertEquals(LumenAcrylicFallbackReason.NONE, reason())
        assertEquals(LumenAcrylicFallbackReason.DISABLED, reason(enabled = false, reduce = true))
        assertEquals(LumenAcrylicFallbackReason.REDUCE_TRANSPARENCY, reason(reduce = true, lowRam = true))
        assertEquals(LumenAcrylicFallbackReason.LOW_RAM_DEVICE, reason(lowRam = true, power = true))
        assertEquals(LumenAcrylicFallbackReason.POWER_SAVE, reason(power = true, inactive = true))
        assertEquals(LumenAcrylicFallbackReason.WINDOW_INACTIVE, reason(inactive = true, backdrop = false))
        assertEquals(LumenAcrylicFallbackReason.NO_BACKDROP, reason(backdrop = false))
        assertFalse(FluentAcrylicPolicy.releasesBackdrop(LumenAcrylicFallbackReason.NONE))
        assertFalse(FluentAcrylicPolicy.releasesBackdrop(LumenAcrylicFallbackReason.WINDOW_INACTIVE))
        assertFalse(FluentAcrylicPolicy.releasesBackdrop(LumenAcrylicFallbackReason.BACKDROP_UNAVAILABLE))
        assertTrue(FluentAcrylicPolicy.releasesBackdrop(LumenAcrylicFallbackReason.POWER_SAVE))
        assertTrue(FluentAcrylicPolicy.releasesBackdrop(LumenAcrylicFallbackReason.REDUCE_TRANSPARENCY))
    }

    @Test fun hsvRoundTrips() {
        for (color in intArrayOf(0xFF0067C0.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF2C2C2C.toInt(), 0xFFFFC0CB.toInt())) {
            val hsv = FluentAcrylicPolicy.hsv(color)
            assertEquals(color, FluentAcrylicPolicy.hsvToRgb(hsv[0], hsv[1], hsv[2]))
        }
    }

    @Test fun elevationMappingAndPressedState() {
        assertEquals(1, FluentElevationPolicy.fluentValue(LumenFluentElevation.CONTROL, pressed = true, pressedLowers = true))
        assertEquals(2, FluentElevationPolicy.fluentValue(LumenFluentElevation.CONTROL, pressed = true, pressedLowers = false))
        assertEquals(1, FluentElevationPolicy.fluentValue(LumenFluentElevation.LAYER, pressed = true, pressedLowers = true))
        assertEquals(16, FluentElevationPolicy.fluentValue(LumenFluentElevation.FLYOUT, pressed = true, pressedLowers = true))
        assertEquals(0.5f, FluentElevationPolicy.androidElevationDp(2), 0f)
        assertEquals(2f, FluentElevationPolicy.androidElevationDp(8), 0f)
        assertEquals(8f, FluentElevationPolicy.androidElevationDp(32), 0f)
        assertEquals(24f, FluentElevationPolicy.androidElevationDp(128), 0f)
        assertEquals(4f, FluentElevationPolicy.cornerDp(LumenFluentElevation.CONTROL), 0f)
        assertEquals(8f, FluentElevationPolicy.cornerDp(LumenFluentElevation.FLYOUT), 0f)
    }

    @Test fun strokeFollowsControlElevationBorderBrush() {
        val light = FluentElevationPolicy.stroke(dark = false, accent = false, enabled = true)
        assertEquals(0x29000000, light.band); assertEquals(0x0F000000, light.body); assertTrue(light.bandAtBottom)
        val dark = FluentElevationPolicy.stroke(dark = true, accent = false, enabled = true)
        assertEquals(0x18FFFFFF, dark.band); assertEquals(0x12FFFFFF, dark.body); assertFalse(dark.bandAtBottom)
        val accentDark = FluentElevationPolicy.stroke(dark = true, accent = true, enabled = true)
        assertEquals(0x23000000, accentDark.band); assertEquals(0x14FFFFFF, accentDark.body); assertTrue(accentDark.bandAtBottom)
        val disabled = FluentElevationPolicy.stroke(dark = false, accent = false, enabled = false)
        assertEquals(disabled.body, disabled.band)
    }

    @Test fun stateAndFocusTokens() {
        assertEquals(0, FluentElevationPolicy.subtleFill(dark = true, hovered = false, pressed = false))
        assertEquals(0x0FFFFFFF, FluentElevationPolicy.subtleFill(dark = true, hovered = true, pressed = false))
        assertEquals(0x06000000, FluentElevationPolicy.subtleFill(dark = false, hovered = true, pressed = true))
        assertEquals(0xE4000000.toInt(), FluentElevationPolicy.focusOuter(dark = false))
        assertEquals(0xB3000000.toInt(), FluentElevationPolicy.focusInner(dark = true))
    }

    @Test fun transitionIsInterruptibleWithoutJumps() {
        val t = FluentTransition(0f)
        t.retarget(1f, 0L, 167L, eased = false)
        assertEquals(0.5f, t.value(83L, eased = false), 0.01f)
        assertTrue(t.isRunning(83L))
        t.retarget(0f, 83L, 167L, eased = false)
        assertEquals(0.5f, t.value(83L, eased = false), 0.01f)   // reversal continues from the current value
        assertEquals(0f, t.value(83L + 84L, eased = false), 0f)
        assertFalse(t.isRunning(83L + 84L))
        t.retarget(1f, 500L, 0L, eased = true)
        assertEquals(1f, t.value(500L, eased = true), 0f)        // zero duration snaps (reduce motion)
        assertEquals(3L, t.retargets)
    }

    @Test fun availabilityGainsAtOnceAndLosesAfterHysteresis() {
        val a = AcrylicAvailability(120L)
        assertFalse(a.report(sampled = false, now = 0L))
        assertTrue(a.report(sampled = true, now = 10L)); assertTrue(a.available)
        assertFalse(a.report(sampled = false, now = 20L)); assertEquals(120L, a.pendingLossIn(20L))
        assertFalse(a.report(sampled = true, now = 30L)); assertEquals(-1L, a.pendingLossIn(30L))
        assertFalse(a.report(sampled = false, now = 40L))
        assertFalse(a.report(sampled = false, now = 159L)); assertTrue(a.available)
        assertTrue(a.report(sampled = false, now = 160L)); assertFalse(a.available)
    }

    @Test fun optionBoundsRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { LumenAcrylicOptions(tintOpacity = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LumenAcrylicOptions(blurRadiusDp = 49f) }
        assertThrows(IllegalArgumentException::class.java) { LumenAcrylicOptions(noiseOpacity = 0.2f) }
        assertThrows(IllegalArgumentException::class.java) { LumenFluentElevationOptions(strokeWidthDp = 3f) }
        assertThrows(IllegalArgumentException::class.java) { LumenFluentControlOptions(cornerRadiusDp = -1f) }
    }
}
