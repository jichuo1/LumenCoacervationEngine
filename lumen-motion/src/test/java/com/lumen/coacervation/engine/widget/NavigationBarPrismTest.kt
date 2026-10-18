package com.lumen.coacervation.engine.widget

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.motion.LumenSpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prism selection box: reference math, mask budget, and the classic bar staying untouched. */
class NavigationBarPrismTest {
    @Test fun velocitySquashMatchesTheReferenceAndIsBounded() {
        // v = 1 range/s → /10 → x: ÷(1 − 0.075) wider, y: ×(1 − 0.025) shorter.
        assertEquals(1f / (1f - 0.075f), NavigationBarPrismPolicy.squashX(1f, 1f), 1e-6f)
        assertEquals(1f - 0.025f, NavigationBarPrismPolicy.squashY(1f, 1f), 1e-6f)
        // Backwards is signed, as upstream: narrower and taller.
        assertTrue(NavigationBarPrismPolicy.squashX(-1f, 1f) < 1f)
        assertTrue(NavigationBarPrismPolicy.squashY(-1f, 1f) > 1f)
        // Clamped to ±0.2 on both axes, whatever the speed or multiplier.
        assertEquals(1.25f, NavigationBarPrismPolicy.squashX(1000f, 2f), 1e-6f)
        assertEquals(0.8f, NavigationBarPrismPolicy.squashY(1000f, 2f), 1e-6f)
        assertEquals(1f / 1.2f, NavigationBarPrismPolicy.squashX(-1000f, 2f), 1e-6f)
        // Deformation 0 switches it off.
        assertEquals(1f, NavigationBarPrismPolicy.squashX(5f, 0f), 0f)
        assertEquals(1f, NavigationBarPrismPolicy.squashY(5f, 0f), 0f)
    }

    @Test fun velocityIsInRangeFractionsAndSmoothsWithoutOvershoot() {
        assertEquals(1f, NavigationBarPrismPolicy.rangeVelocity(3f, 4), 0f)
        assertEquals(3f, NavigationBarPrismPolicy.rangeVelocity(3f, 1), 0f)
        assertEquals(0f, NavigationBarPrismPolicy.rangeVelocity(Float.NaN, 4), 0f)
        val step = NavigationBarPrismPolicy.smooth(0f, 1f, 0.016f)
        assertTrue(step > 0f && step < 1f)
        assertEquals(0.5f, NavigationBarPrismPolicy.smooth(0.5f, 1f, 0f), 0f)
        assertTrue(NavigationBarPrismPolicy.smooth(0f, 1f, 1f) > 0.999f)
    }

    @Test fun maskStaysWithinItsByteBudget() {
        assertEquals(1f, NavigationBarPrismPolicy.maskScale(1080, 192), 0f)
        assertEquals(0f, NavigationBarPrismPolicy.maskScale(0, 192), 0f)
        for ((w, h) in listOf(1440 to 256, 2560 to 320, 4000 to 400)) {
            val s = NavigationBarPrismPolicy.maskScale(w, h)
            val bytes = NavigationBarPrismPolicy.maskSize(w, s).toLong() * NavigationBarPrismPolicy.maskSize(h, s)
            assertTrue("$w x $h -> $bytes", bytes <= NavigationBarPrismPolicy.MAX_MASK_PIXELS)
        }
        assertEquals(512 * 1024, NavigationBarPrismPolicy.MAX_MASK_PIXELS)
    }

    @Test fun settledNeedsBothSpringsAndVelocityAtRest() {
        assertTrue(NavigationBarPrismPolicy.settled(1f, 1f, 1f, 0f, 0f, 0f))
        assertFalse(NavigationBarPrismPolicy.settled(1.01f, 1f, 1f, 0f, 0f, 0f))
        assertFalse(NavigationBarPrismPolicy.settled(1f, 1f, 1f, 0.5f, 0f, 0f))
        assertFalse(NavigationBarPrismPolicy.settled(1f, 1f, 1f, 0f, 0.2f, 0f))
    }

    @Test fun optionsDefaultToTheReferenceAndRejectOutOfRange() {
        val d = LumenPrismSelectionOptions.DEFAULT
        assertEquals(78f / 56f, d.pressedScale, 0f)
        assertEquals(1.2f, d.contentMagnification, 0f)
        assertEquals(10f, d.lensHeightDp, 0f); assertEquals(14f, d.lensAmountDp, 0f); assertEquals(1f, d.dispersion, 0f)
        assertEquals(LumenSpringSpec(250f, .6f), d.scaleXSpring); assertEquals(LumenSpringSpec(250f, .7f), d.scaleYSpring)
        assertThrows(IllegalArgumentException::class.java) { LumenPrismSelectionOptions(pressedScale = 0.9f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismSelectionOptions(contentMagnification = 2f) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismSelectionOptions(dispersion = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LumenPrismSelectionOptions(shadowOpacity = 0.5f) }
    }

    @Test fun classicBarIsUnchangedWithoutPrism() {
        val bar = MotionSource.file("LumenNavigationBar")
        val visuals = bar.after("private fun applyVisuals(").before("private fun applyPrism(")
        // Without a renderer the classic lens scale and the 33-step tint blend apply, exactly as before.
        val classic = visuals.after("if (renderer == null) {").before("} else if (applyPrism(renderer, lensX)) moved = true")
        assertTrue(classic.contains("val scaleX = NavigationBarMotion.lensScaleX(press)"))
        assertTrue(classic.contains("val scaleY = NavigationBarMotion.lensScaleY(press)"))
        assertTrue(visuals.contains("val step = if (renderer != null) 0 else ((1f - abs(displayedPosition - i)).coerceIn(0f, 1f) * 32f).roundToInt()"))
        // The selection View is the plain View it always was; prism fades its drawable, never the View alpha.
        assertTrue(bar.contains("private val selection = View(context).apply {"))
        assertFalse(bar.contains("hasOverlappingRendering"))
        assertFalse(bar.contains("selection.alpha ="))
        // Children draw unclipped unless the prism copy is ready.
        assertTrue(bar.contains("if (renderer == null || !renderer.ready || child !is NavigationItem) return super.drawChild(canvas, child, drawingTime)"))
    }

    @Test fun prismFrameTickTouchesNeitherGlowNorHost() {
        val bar = MotionSource.file("LumenNavigationBar")
        val tick = bar.after("private fun prismFrame() {").before("/** Keeps the jelly springs")
        assertFalse(tick.contains("glow."))
        assertFalse(tick.contains("onVisualMovement"))
        assertFalse(tick.contains("applyVisuals("))
        assertTrue(tick.contains("if (pressAnimator?.isRunning == true || reboundAnimator?.isRunning == true) return"))
        // Pager swipes do not squash the pill; only the user's scrub or its rebound do.
        assertTrue(bar.contains("trackVelocity = (touchActive && gesture.intent == NavigationBarIntent.SCRUB) || indicatorSettling,"))
        // A legibility step only recolours: no mask rebuild.
        val legibility = bar.after("fun setLegibility(").before("private fun resetInteraction()")
        assertFalse(legibility.contains("invalidateMask"))
    }

    @Test fun maskCopiesGlyphCoverageOnly() {
        val renderer = MotionSource.file("PrismSelectionRenderer")
        val mask = renderer.after("private fun rebuildMask(").before("/** Pre-blurred capsule")
        // Screenshot lesson: single-line centred labels sit at their own scroll offset.
        assertTrue(mask.contains("label.compoundPaddingLeft - label.scrollX"))
        // Opaque, shadow-free paints for the copy, restored afterwards.
        assertTrue(mask.contains("paint.color = Color.BLACK"))
        assertTrue(mask.contains("if (shadowRadius > 0f) paint.clearShadowLayer()"))
        assertTrue(mask.contains("paint.color = savedColor"))
        assertTrue(mask.contains("copy.setTintList(null)"))
        assertTrue(mask.contains("copy.state = source.state"))
        // Rebuilds happen only when the recorded geometry changed.
        assertTrue(renderer.contains("if (!maskDirty && mask != null && signatureMatches(width, height, items, icons, labels)) return false"))
    }

    @Test fun prismDrawPathAllocatesNothingAndKeepsPlatformTypesIsolated() {
        val renderer = MotionSource.file("PrismSelectionRenderer")
        val draw = renderer.after("fun draw(canvas: Canvas) {").before("/** Springs at their target")
        for (token in listOf("Paint", "LinearGradient", "BitmapShader", "Matrix", "Path", "RectF", "Bitmap.createBitmap", "BlurMaskFilter", "LumenSpring")) {
            assertFalse(token, Regex("(?<![A-Za-z.])$token\\(").containsMatchIn(draw))
        }
        val layout = renderer.after("fun layoutPill(").before("/** Scale the host's selection View")
        assertFalse(Regex("(?<![A-Za-z.])LumenSpring\\(").containsMatchIn(layout))
        assertFalse(renderer.contains("RuntimeShader"))
        assertFalse(renderer.contains(".recycle()"))
        // The shader compiles in configure (not draw) and a failure only disables refraction.
        assertTrue(renderer.after("fun configure(").before("fun setColor(").contains("catch (error: RuntimeException)"))
        val shader = MotionSource.file("PrismSelectionShaderApi33")
        assertTrue(shader.contains("@RequiresApi(33)\ninternal class PrismSelectionShaderApi33"))
        assertTrue(shader.contains("Copyright 2025 Kyant"))
        assertFalse(shader.contains("normalize("))
        assertTrue(shader.contains("if (color != appliedColor || !colorApplied) {"))
    }

    @Test fun shadowTextureStaysWithinBudget() {
        assertEquals(3f * (0.57735f * 72f + 0.5f), NavigationBarPrismPolicy.shadowExtent(72f), 1e-4f)
        for ((w, h) in listOf(600 to 500, 1200 to 900)) {
            val s = NavigationBarPrismPolicy.shadowScale(w, h)
            assertTrue(NavigationBarPrismPolicy.maskSize(w, s).toLong() * NavigationBarPrismPolicy.maskSize(h, s) <= NavigationBarPrismPolicy.MAX_SHADOW_PIXELS)
        }
    }

    /** Device lesson (API 31 emulator): ValueAnimator.start() updates synchronously, so the frame source must be published first. */
    @Test fun frameSourceIsPublishedBeforeItStarts() {
        val frames = MotionSource.file("LumenNavigationBar").after("private fun startPrismFrames() {").before("private fun stopPrismFrames()")
        assertTrue(frames.indexOf("prismFrames = frames") in 0 until frames.indexOf("frames.start()"))
    }

}
