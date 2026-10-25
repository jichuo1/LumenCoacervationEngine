package com.lumen.coacervation.engine.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LumenSliderPolicyTest {
    private val p = LumenSliderPolicy

    @Test fun valueAndFractionRoundTripAndClamp() {
        assertEquals(.25f, p.fraction(25f, 0f, 100f), 1e-6f)
        assertEquals(0f, p.fraction(-5f, 0f, 100f), 0f)
        assertEquals(1f, p.fraction(500f, 0f, 100f), 0f)
        assertEquals(0f, p.fraction(Float.NaN, 0f, 100f), 0f)
        assertEquals(0f, p.fraction(3f, 2f, 2f), 0f)
        assertEquals(40f, p.valueAt(.4f, 20f, 70f), 1e-4f)
        // fraction 1 must return exactly `to`, not a float approximation.
        assertEquals(.3f, p.valueAt(1f, .1f, .3f), 0f)
    }

    @Test fun snapUsesWholeStepsAndATailStopAtTo() {
        assertEquals(0f, p.snap(.4f, 0f, 10f, 1f), 0f)
        assertEquals(1f, p.snap(.6f, 0f, 10f, 1f), 0f)
        assertEquals(10f, p.snap(12f, 0f, 10f, 1f), 0f)
        // 0..10 step 3 → 0, 3, 6, 9, then 10 as the tail stop.
        assertEquals(5, p.stopCount(0f, 10f, 3f))
        assertEquals(9f, p.snap(9.4f, 0f, 10f, 3f), 0f)
        assertEquals(10f, p.snap(9.6f, 0f, 10f, 3f), 0f)
        assertEquals(10f, p.stopValue(4, 0f, 10f, 3f), 0f)
        // Exact multiples have no tail stop, and float noise does not create one.
        assertEquals(11, p.stopCount(0f, 1f, .1f))
        assertEquals(1f, p.snap(.97f, 0f, 1f, .1f), 1e-6f)
        // Continuous values are only clamped.
        assertEquals(.123f, p.snap(.123f, 0f, 1f, 0f), 0f)
        assertEquals(0, p.stopCount(0f, 1f, 0f))
        assertEquals(-1, p.stopIndex(.5f, 0f, 1f, 0f))
    }

    @Test fun magnetPullsTowardTheNearestStopContinuouslyAndMonotonically() {
        // Identity when continuous or strength 0.
        assertEquals(.37f, p.magnet(.37f, 0f, 1f, 0f, 1f), 0f)
        assertEquals(.37f, p.magnet(.37f, 0f, 1f, .1f, 0f), 0f)
        // On a stop it stays; inside the half step it is pulled in; at the half step boundary it is continuous.
        assertEquals(.5f, p.magnet(.5f, 0f, 1f, .1f, 1f), 1e-6f)
        val near = p.magnet(.52f, 0f, 1f, .1f, 1f)
        assertTrue(near > .5f && near < .52f)
        assertEquals(.55f, p.magnet(.5499f, 0f, 1f, .1f, 1f), 1e-3f)
        assertEquals(.55f, p.magnet(.5501f, 0f, 1f, .1f, 1f), 1e-3f)
        var last = -1f
        var f = 0f
        while (f <= 1f) {
            val m = p.magnet(f, 0f, 1f, .1f, .5f)
            assertTrue("monotonic at $f", m >= last - 1e-6f)
            assertTrue(m in 0f..1f)
            last = m
            f += .001f
        }
    }

    @Test fun physicalCoordinatesMirrorInRtl() {
        assertEquals(30f, p.physicalX(.25f, 10f, 90f, rtl = false), 1e-5f)
        assertEquals(70f, p.physicalX(.25f, 10f, 90f, rtl = true), 1e-5f)
        assertEquals(.25f, p.fractionAt(30f, 10f, 90f, rtl = false), 1e-5f)
        assertEquals(.25f, p.fractionAt(70f, 10f, 90f, rtl = true), 1e-5f)
        // Unclamped outside the track: the rubber band input.
        assertTrue(p.fractionAt(0f, 10f, 90f, rtl = false) < 0f)
        assertEquals(.75f, p.dragFraction(.5f, 20f, 80f, rtl = false), 1e-6f)
        assertEquals(.25f, p.dragFraction(.5f, 20f, 80f, rtl = true), 1e-6f)
    }

    @Test fun rubberBandIsBoundedSignedAndStartsAtHalfSlope() {
        val limit = 18f
        assertEquals(0f, p.rubberBand(0f, limit), 0f)
        assertTrue(p.rubberBand(1e6f, limit) < limit)
        assertEquals(-p.rubberBand(30f, limit), p.rubberBand(-30f, limit), 1e-6f)
        assertEquals(.5f, p.rubberBand(.01f, limit) / .01f, 1e-3f)
        assertEquals(0f, p.overshootPx(.5f, 100f), 0f)
        assertEquals(-20f, p.overshootPx(-.2f, 100f), 1e-4f)
        assertEquals(30f, p.overshootPx(1.3f, 100f), 1e-4f)
        assertEquals(1f, p.stretchScaleAcross(0f, limit), 0f)
        assertEquals(1f - LumenSliderPolicy.STRETCH_SQUEEZE, p.stretchScaleAcross(limit * 9f, limit), 1e-6f)
        assertEquals(1.05f, p.stretchScaleAlong(-10f, 200f), 1e-6f)
    }

    @Test fun stretchPivotsAtTheOppositeEndInBothDirections() {
        // LTR: over the high end (right) pins the left edge; over the low end pins the right.
        assertEquals(0f, p.stretchPivot(5f, rtl = false), 0f)
        assertEquals(1f, p.stretchPivot(-5f, rtl = false), 0f)
        // RTL: the high end is on the left.
        assertEquals(1f, p.stretchPivot(5f, rtl = true), 0f)
        assertEquals(0f, p.stretchPivot(-5f, rtl = true), 0f)
    }

    @Test fun keyboardStepsFollowStepOrPercent() {
        assertEquals(1f, p.keyStep(0f, 100f, 0f), 1e-6f)
        assertEquals(5f, p.keyStep(0f, 100f, 5f), 0f)
        assertEquals(10f, p.pageStep(0f, 100f, 0f), 1e-6f)
        assertEquals(10f, p.pageStep(0f, 100f, 5f), 0f)
        assertEquals(1f, p.pageStep(0f, 4f, 1f), 0f)
        assertEquals(100f, p.keyValue(99.5f, 5f, 0f, 100f, 5f), 0f)
        assertEquals(0f, p.keyValue(2f, -10f, 0f, 100f, 0f), 0f)
    }

    @Test fun ticksHideWhenTooDenseOrTooMany() {
        assertTrue(p.ticksVisible(5, 400f, 30f))
        assertFalse(p.ticksVisible(5, 100f, 30f))
        assertFalse(p.ticksVisible(LumenSliderPolicy.MAX_VISIBLE_TICKS + 1, 1e6f, 1f))
        assertFalse(p.ticksVisible(1, 400f, 1f))
    }

    @Test fun labelFollowsTheThumbButStaysNearTheControl() {
        assertEquals(100f, p.labelCenter(100f, 40f, 300f, 6f), 0f)
        assertEquals(14f, p.labelCenter(0f, 40f, 300f, 6f), 0f)
        assertEquals(286f, p.labelCenter(300f, 40f, 300f, 6f), 0f)
        assertEquals(10f, p.labelCenter(3f, 60f, 20f, 0f), 0f)
        assertEquals("40", p.defaultLabel(40.2f, 1f))
        assertEquals("0.5", p.defaultLabel(.5f, .1f))
        assertEquals("0.33", p.defaultLabel(.333f, 0f))
        assertEquals("150", p.defaultLabel(150.4f, 0f))
    }

    @Test fun enlargedLensStaysInsideTheGroove() {
        // At rest (scale 1) the lens sits exactly on the knob, even at the very end of travel.
        assertEquals(380f, p.lensCenter(380f, 20f, 1f, 0f, 400f), 0f)
        // Pressed 1.45x at the end: half width 29 must fit, so the centre moves in to 371.
        assertEquals(371f, p.lensCenter(380f, 20f, 1.45f, 0f, 400f), 1e-4f)
        assertEquals(29f, p.lensCenter(20f, 20f, 1.45f, 0f, 400f), 1e-4f)
        // In the middle nothing moves.
        assertEquals(200f, p.lensCenter(200f, 20f, 1.45f, 0f, 400f), 0f)
        // A groove too short for the lens keeps it centred.
        assertEquals(15f, p.lensCenter(5f, 20f, 1.45f, 0f, 30f), 0f)
    }

    @Test fun prismCapsuleLensOverhangsAQuarterUntilFull() {
        val w = 40f
        // Fill passes the lens centre by a quarter width: the outer quarter stays outside the fill.
        assertEquals(10f, p.prismCapsuleWrap(0f, w, 6f), 1e-5f)
        assertEquals(10f, p.prismCapsuleWrap(.9f, w, 6f), 1e-5f)
        // Full value wraps the lens and reaches the groove end.
        assertEquals(26f, p.prismCapsuleWrap(1f, w, 6f), 1e-5f)
        var last = 0f
        for (i in 0..100) {
            val v = p.prismCapsuleWrap(i / 100f, w, 6f)
            assertTrue(v >= last - 1e-6f); last = v
        }
    }

    @Test fun hdrGainNeverExceedsHeadroomOrDisplay() {
        assertEquals(1f, p.hdrGain(3f, 2f, available = false), 0f)
        assertEquals(1f, p.hdrGain(1f, 2f, available = true), 0f)
        assertEquals(1f, p.hdrGain(Float.NaN, 2f, available = true), 0f)
        assertEquals(1.6f, p.hdrGain(1.6f, 2f, available = true), 0f)
        assertEquals(2f, p.hdrGain(5f, 2f, available = true), 0f)
        assertEquals(LumenSliderPolicy.MAX_HDR_HEADROOM, p.hdrGain(16f, 99f, available = true), 0f)
        assertEquals(0, p.highlightAlpha(0f))
        assertEquals((LumenSliderPolicy.HIGHLIGHT_ALPHA * 255f + .5f).toInt(), p.highlightAlpha(1f))
    }

    @Test fun fluentTokensMatchWinUi() {
        // Slider_themeresources.xaml (microsoft-ui-xaml 8463f451): inner thumb storyboard targets.
        assertEquals(.86f, p.fluentInnerScale(enabled = true, pressed = false, hovered = false), 0f)
        assertEquals(1.167f, p.fluentInnerScale(enabled = true, pressed = false, hovered = true), 0f)
        assertEquals(.71f, p.fluentInnerScale(enabled = true, pressed = true, hovered = true), 0f)
        assertEquals(1.167f, p.fluentInnerScale(enabled = false, pressed = true, hovered = false), 0f)
        assertEquals(250L, p.fluentInnerDurationMs(enabled = true, pressed = true, hovered = false))
        assertEquals(167L, p.fluentInnerDurationMs(enabled = true, pressed = false, hovered = false))
        assertEquals(22f, LumenSliderPolicy.FLUENT_THUMB_DP, 0f)
        assertEquals(12f, LumenSliderPolicy.FLUENT_INNER_DP, 0f)
        assertEquals(4f, LumenSliderPolicy.FLUENT_TRACK_DP, 0f)
        // Common_themeresources_any.xaml colours.
        assertEquals(0x72000000, FluentSliderTokens.strongFill(dark = false, enabled = true))
        assertEquals(0x8BFFFFFF.toInt(), FluentSliderTokens.strongFill(dark = true, enabled = true))
        assertEquals(0x51000000, FluentSliderTokens.strongFill(dark = false, enabled = false))
        assertEquals(0xFF454545.toInt(), FluentSliderTokens.solidFill(dark = true))
        val accent = 0xFF005FB8.toInt()
        assertEquals(accent, FluentSliderTokens.accent(accent, false, true, false, false))
        assertEquals(230, FluentSliderTokens.accent(accent, false, true, false, true) ushr 24)
        assertEquals(204, FluentSliderTokens.accent(accent, false, true, true, false) ushr 24)
        assertEquals(0x37000000, FluentSliderTokens.accent(accent, false, false, false, false))
    }

    @Test fun fastOutSlowInIsTheWinUiSpline() {
        assertEquals(0f, p.fastOutSlowIn(0f), 0f)
        assertEquals(1f, p.fastOutSlowIn(1f), 0f)
        // cubic-bezier(0,0,0,1) front-loads: halfway in time is far past halfway in value.
        assertTrue(p.fastOutSlowIn(.5f) > .85f)
        var last = 0f
        for (i in 1..100) {
            val y = p.fastOutSlowIn(i / 100f)
            assertTrue(y >= last)
            last = y
        }
    }

    @Test fun defaultsResolvePerAppearance() {
        assertEquals(LumenSliderShape.CAPSULE, p.resolveShape(LumenSliderShape.AUTO, SliderKind.SKIN))
        assertEquals(LumenSliderShape.CLASSIC, p.resolveShape(LumenSliderShape.AUTO, SliderKind.PRISM))
        assertEquals(LumenSliderShape.CAPSULE, p.resolveShape(LumenSliderShape.AUTO, SliderKind.PLAIN))
        assertEquals(LumenSliderShape.CLASSIC, p.resolveShape(LumenSliderShape.AUTO, SliderKind.FLUENT))
        assertEquals(LumenSliderShape.CLASSIC, p.resolveShape(LumenSliderShape.AUTO, SliderKind.MICA))
        assertEquals(LumenSliderShape.CLASSIC, p.resolveShape(LumenSliderShape.CLASSIC, SliderKind.SKIN))
        assertTrue(p.showsLabel(LumenSliderValueLabel.AUTO, SliderKind.FLUENT))
        assertFalse(p.showsLabel(LumenSliderValueLabel.AUTO, SliderKind.SKIN))
        assertTrue(p.hdrEnabled(LumenSliderHdr.AUTO, SliderKind.SKIN))
        assertFalse(p.hdrEnabled(LumenSliderHdr.AUTO, SliderKind.MICA))
        // The highlight would sit right under the Prism lens and wash the refraction white.
        assertFalse(p.hdrEnabled(LumenSliderHdr.AUTO, SliderKind.PRISM))
        assertTrue(p.hdrEnabled(LumenSliderHdr.ON, SliderKind.PRISM))
        assertTrue(p.hdrEnabled(LumenSliderHdr.ON, SliderKind.FLUENT))
    }

    @Test fun colourMathIsPureAndThemeFollowsBackground() {
        assertTrue(p.isDark(0xFF121212.toInt()))
        assertFalse(p.isDark(0xFFF3F3F3.toInt()))
        assertEquals(0x80FFFFFF.toInt(), p.withAlpha(0xFFFFFFFF.toInt(), 128))
        assertEquals(0xFF808080.toInt(), p.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), .5f))
        assertEquals(102, p.scaleAlpha(0xCC000000.toInt(), .5f) ushr 24)
    }

    @Test fun optionsAndRangesRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { LumenSliderRange(1f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSliderRange(0f, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LumenSliderRange(0f, 1f, -1f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSliderRange(0f, 1f, 2f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSliderRange(0f, 1e6f, .1f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSliderOptions(capsuleHeightDp = 12f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSliderOptions(hdrHeadroom = .5f) }
        assertThrows(IllegalArgumentException::class.java) { LumenSliderOptions(magnetStrength = 2f) }
        LumenSliderRange(0f, 100f, 5f)
    }
}

class LumenSliderGestureTest {
    @Test fun horizontalPastSlopDragsAndVerticalYields() {
        val g = LumenSliderGesture()
        g.begin()
        assertEquals(SliderIntent.NONE, g.move(3f, 1f, 8f))
        assertEquals(SliderIntent.DRAG, g.move(12f, 2f, 8f))
        // Decided once: later vertical movement does not flip it.
        assertEquals(SliderIntent.DRAG, g.move(0f, 80f, 8f))
        assertFalse(g.finish(cancelled = false))

        g.begin()
        assertEquals(SliderIntent.YIELD, g.move(4f, 12f, 8f))
        assertFalse(g.finish(cancelled = false))

        // Diagonal within the 1.15 bias yields, so scrolling containers keep vertical-ish drags.
        g.begin()
        assertEquals(SliderIntent.YIELD, g.move(10f, 9f, 8f))
    }

    @Test fun onlyAnUndecidedReleaseIsATap() {
        val g = LumenSliderGesture()
        g.begin()
        assertTrue(g.finish(cancelled = false))
        g.begin()
        assertFalse(g.finish(cancelled = true))
        assertFalse(g.finish(cancelled = false))
        g.begin()
        assertEquals(SliderIntent.YIELD, g.move(Float.NaN, 0f, 8f))
    }
}
