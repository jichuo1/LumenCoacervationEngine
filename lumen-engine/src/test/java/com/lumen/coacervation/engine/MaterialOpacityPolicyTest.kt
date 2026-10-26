package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.runtime.MaterialKind
import com.lumen.coacervation.engine.runtime.MaterialOpacityFrame
import com.lumen.coacervation.engine.runtime.MaterialOpacityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class MaterialOpacityPolicyTest {

    private fun channels(f: MaterialOpacityFrame) = floatArrayOf(
        f.fill, f.luminosity, f.backdrop, f.noise, f.scatterStrength, f.scatterRadius, f.refraction, f.specular,
        f.chromatic, f.saturation, f.highlight, f.innerShadow, f.shadow, f.edge, f.elevation, f.image,
        f.blurRadius, f.radiusBackdrop)

    private fun frame(kind: MaterialKind, p: Float) = MaterialOpacityFrame().also { MaterialOpacityPolicy.frame(kind, p, it) }

    @Test fun fullOpacityIsTheCurrentDefaultForEveryChannel() {
        for (kind in MaterialKind.values()) {
            val f = frame(kind, 1f)
            assertTrue(kind.name, f.isDefault)
            for (value in channels(f)) assertEquals(kind.name, 1f, value, 0f)
        }
    }

    @Test fun zeroOpacityEndpointsMatchTheMappingTable() {
        val frosted = frame(MaterialKind.FROSTED, 0f)
        assertEquals(0f, frosted.fill, 0f); assertEquals(0f, frosted.backdrop, 0f)
        assertEquals(0.4f, frosted.edge, 1e-6f); assertEquals(0f, frosted.shadow, 0f)
        val liquid = frame(MaterialKind.LIQUID, 0f)
        assertEquals(0f, liquid.scatterStrength, 0f); assertEquals(0.5f, liquid.refraction, 1e-6f)
        assertEquals(0.6f, liquid.specular, 1e-6f); assertEquals(0.5f, liquid.chromatic, 1e-6f)
        val fluent = frame(MaterialKind.FLUENT, 0f)
        assertEquals(0f, fluent.backdrop, 0f); assertEquals(0f, fluent.noise, 0f)
        assertEquals(1f, fluent.edge, 0f); assertEquals(0.5f, fluent.elevation, 1e-6f)
        val mica = frame(MaterialKind.MICA, 0f)
        assertEquals(0f, mica.image, 0f); assertEquals(1f, mica.fill, 0f)
        val prism = frame(MaterialKind.PRISM, 0f)
        assertEquals(0f, prism.fill, 0f); assertEquals(0.6f, prism.highlight, 1e-6f); assertEquals(0f, prism.shadow, 0f)
        val plain = frame(MaterialKind.PLAIN, 0f)
        assertEquals(0f, plain.fill, 0f); assertEquals(0.4f, plain.edge, 1e-6f)
    }

    @Test fun everyChannelIsMonotoneAndContinuous() {
        for (kind in MaterialKind.values()) {
            var previous = channels(frame(kind, 0f))
            for (i in 1..1000) {
                val current = channels(frame(kind, i / 1000f))
                for (c in current.indices) {
                    assertTrue("${kind.name} channel $c falls at ${i / 1000f}", current[c] + 1e-6f >= previous[c])
                    // No step function: the steepest step is the first one of the root curves (sqrt(0.001) ≈ 0.032).
                    assertTrue("${kind.name} channel $c jumps at ${i / 1000f}", current[c] - previous[c] <= 0.035f)
                }
                previous = current
            }
        }
    }

    @Test fun outlinesLeaveLast() {
        for (kind in MaterialKind.values()) for (i in 0..100) {
            val f = frame(kind, i / 100f)
            val outline = minOf(f.edge, f.highlight)
            // Inner shadow is a prism channel and scattering a liquid one; other kinds leave them at 1.
            val inner = if (kind == MaterialKind.PRISM) f.innerShadow else 0f
            val scatter = if (kind == MaterialKind.LIQUID) f.scatterStrength else 0f
            // Plain surfaces sample nothing; their backdrop weight stays 1 so a static fallback tint is not thinned twice.
            val backdrop = if (kind == MaterialKind.PLAIN) 0f else f.backdrop
            for (body in floatArrayOf(f.fill, backdrop, f.shadow, inner, scatter))
                assertTrue("${kind.name} at ${i / 100f}", outline + 1e-6f >= body)
        }
    }

    @Test fun micaStaysOpaque() {
        for (i in 0..100) {
            val f = frame(MaterialKind.MICA, i / 100f)
            // Only the image weight over the opaque base changes; the base (fill) and outline never thin out.
            assertEquals(1f, f.fill, 0f); assertEquals(1f, f.backdrop, 0f); assertEquals(1f, f.edge, 0f)
        }
    }

    @Test fun fluentKeepsItsBlurUntilTheGate() {
        assertEquals(1f, frame(MaterialKind.FLUENT, MaterialOpacityPolicy.FLUENT_BLUR_GATE).backdrop, 0f)
        assertEquals(1f, frame(MaterialKind.FLUENT, 0.6f).backdrop, 0f)
        assertTrue(frame(MaterialKind.FLUENT, 0.6f).fill < 1f)
        assertTrue(frame(MaterialKind.FLUENT, 0.2f).backdrop in 0.01f..0.99f)
    }

    @Test fun inputIsSanitised() {
        assertEquals(0.4f, MaterialOpacityPolicy.sanitize(Float.NaN, 0.4f), 0f)
        assertEquals(1f, MaterialOpacityPolicy.sanitize(Float.POSITIVE_INFINITY, 0.4f), 0f)
        assertEquals(0f, MaterialOpacityPolicy.sanitize(Float.NEGATIVE_INFINITY, 0.4f), 0f)
        assertEquals(0f, MaterialOpacityPolicy.sanitize(-3f, 0.4f), 0f)
        assertEquals(0.25f, MaterialOpacityPolicy.sanitize(0.25f, 0.4f), 0f)
    }

    @Test fun textFloorAndReducedTransparency() {
        for (kind in MaterialKind.values()) {
            val floor = MaterialOpacityPolicy.staticFloor(kind)
            assertEquals(floor, MaterialOpacityPolicy.effective(kind, 0f, carriesText = true, reduceTransparency = false), 0f)
            assertEquals(0f, MaterialOpacityPolicy.effective(kind, 0f, carriesText = false, reduceTransparency = false), 0f)
            assertEquals(0.9f, MaterialOpacityPolicy.effective(kind, 0.9f, carriesText = true, reduceTransparency = false), 0f)
            assertEquals(1f, MaterialOpacityPolicy.effective(kind, 0f, carriesText = true, reduceTransparency = true), 0f)
        }
        val out = MaterialOpacityFrame()
        MaterialOpacityPolicy.resolve(MaterialKind.FLUENT, 0.1f, carriesText = true, reduceTransparency = false, out)
        assertEquals(0.1f, out.requested, 0f)
        assertEquals(0.3f, out.effective, 1e-6f)
        MaterialOpacityPolicy.resolve(MaterialKind.FLUENT, 0.1f, carriesText = true, reduceTransparency = true, out)
        assertTrue(out.isDefault)
    }

    @Test fun samplingStopsOnlyWhenTheBackdropIsInvisible() {
        assertFalse(MaterialOpacityPolicy.needsBackdrop(frame(MaterialKind.FROSTED, 0f)))
        assertFalse(MaterialOpacityPolicy.needsBackdrop(frame(MaterialKind.FLUENT, 0f)))
        assertTrue(MaterialOpacityPolicy.needsBackdrop(frame(MaterialKind.FROSTED, 0.05f)))
        assertTrue(MaterialOpacityPolicy.needsBackdrop(frame(MaterialKind.MICA, 0f)))
        for (kind in MaterialKind.values()) assertTrue(MaterialOpacityPolicy.needsBackdrop(frame(kind, 1f)))
    }

    @Test fun drawableStateMultipliesWithItsRoleAndResolvesOnlyOnChange() {
        val state = com.lumen.coacervation.engine.runtime.MaterialOpacityState(MaterialKind.FROSTED)
        // A legibility change counts as a change; repeating the same values does not.
        assertTrue(state.set(1f, carriesText = false))
        assertFalse(state.set(1f, carriesText = false))
        assertEquals(null, state.frame(1f))
        assertTrue(state.set(0.5f, carriesText = false))
        val half = state.frame(1f)!!
        assertEquals(0.5f, half.effective, 0f)
        // Same product, same frame object and values: no re-resolve.
        assertTrue(state.frame(1f) === half)
        val quarter = state.frame(0.5f)!!
        assertEquals(0.25f, quarter.effective, 1e-6f)
        assertTrue(state.set(0.5f, carriesText = true))
        assertEquals(MaterialOpacityPolicy.staticFloor(MaterialKind.FROSTED), state.frame(0.1f)!!.effective, 1e-6f)
        assertFalse(state.set(Float.NaN, carriesText = true))
        assertEquals(0.5f, state.own, 0f)
    }

    @Test fun blurLevelsAreHalfAnOctaveApart() {
        assertEquals(0, MaterialOpacityPolicy.blurLevel(0f))
        assertEquals(0, MaterialOpacityPolicy.blurLevel(Float.NaN))
        assertEquals(MaterialOpacityPolicy.BLUR_LEVELS, MaterialOpacityPolicy.blurLevel(1f))
        assertEquals(1f, MaterialOpacityPolicy.levelFraction(MaterialOpacityPolicy.BLUR_LEVELS), 0f)
        assertEquals(0f, MaterialOpacityPolicy.levelFraction(0), 0f)
        // Monotone, and every level maps back to itself.
        var previous = 0
        for (i in 0..1000) {
            val level = MaterialOpacityPolicy.blurLevel(i / 1000f)
            assertTrue(level >= previous); previous = level
        }
        for (level in 0..MaterialOpacityPolicy.BLUR_LEVELS)
            assertEquals(level, MaterialOpacityPolicy.blurLevel(MaterialOpacityPolicy.levelFraction(level)))
        for (level in 2..MaterialOpacityPolicy.BLUR_LEVELS)
            assertEquals(Math.sqrt(2.0).toFloat(), MaterialOpacityPolicy.levelFraction(level) / MaterialOpacityPolicy.levelFraction(level - 1), 1e-5f)
        // The smallest level is a twelfth of the radius; well below it the blur is off.
        assertEquals(1f / 11.3137f, MaterialOpacityPolicy.levelFraction(1), 1e-4f)
        assertEquals(0, MaterialOpacityPolicy.blurLevel(0.05f))
    }

    @Test fun radiusPathsFollowTheMockupRadii() {
        // Mockup radii: frosted 16·p^0.7, liquid 12·p², fluent 24·smoothstep(p / 0.35), prism 5·p.
        assertEquals(0.5f.pow(0.7f), frame(MaterialKind.FROSTED, 0.5f).blurRadius, 1e-6f)
        assertEquals(0.25f, frame(MaterialKind.LIQUID, 0.5f).blurRadius, 1e-6f)
        assertEquals(1f, frame(MaterialKind.FLUENT, 0.5f).blurRadius, 0f)
        assertEquals(0.5f, frame(MaterialKind.PRISM, 0.5f).blurRadius, 1e-6f)
        for (kind in listOf(MaterialKind.FROSTED, MaterialKind.LIQUID, MaterialKind.FLUENT, MaterialKind.PRISM)) {
            assertEquals(1f, frame(kind, MaterialOpacityPolicy.RADIUS_FADE_END).radiusBackdrop, 0f)
            assertEquals(0f, frame(kind, 0f).radiusBackdrop, 0f)
        }
        assertEquals(1f, frame(MaterialKind.MICA, 0f).blurRadius, 0f)
    }

    @Test fun hazeCurveKeepsVisibleFogAtLowDensity() {
        // Beer–Lambert: a tenth of the density still reads as about a quarter of the fog.
        assertTrue(MaterialOpacityPolicy.haze(0.1f) in 0.2f..0.3f)
        assertEquals(1f, MaterialOpacityPolicy.haze(1f), 0f)
        assertEquals(0f, MaterialOpacityPolicy.haze(0f), 0f)
    }
}
