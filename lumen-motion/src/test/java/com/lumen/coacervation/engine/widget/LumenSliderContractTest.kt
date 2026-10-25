package com.lumen.coacervation.engine.widget

import com.lumen.coacervation.engine.contract.MotionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules the slider can only keep by how it is written (ENGINEERING_RULES §2, §3, §5; 适配标准 §12.1). */
class LumenSliderContractTest {
    private val slider = MotionSource.code(MotionSource.file("LumenSlider"))
    private val policy = MotionSource.code(MotionSource.file("LumenSliderPolicy"))
    private val hdr = MotionSource.code(MotionSource.file("SliderHdrHighlightApi34"))

    private val allocations = listOf("Paint(", "RectF(", "Path(", "Matrix(", "LinearGradient(", "RadialGradient(",
        "PorterDuffColorFilter(", "GradientDrawable(", "LiquidChoiceDrawable(", "intArrayOf(", "floatArrayOf(",
        "String.format", "toString()", "BlurMaskFilter(", "RenderEffect", "saveLayer")

    private fun assertNoAllocation(name: String, body: String) {
        // Word-start match: `clipPath(` is a call on an existing Path, not `Path(`.
        for (token in allocations) {
            assertFalse("$name allocates via $token", Regex("(?<![A-Za-z.])" + Regex.escape(token)).containsMatchIn(body))
        }
    }

    @Test fun drawAndFramePathsAllocateNothing() {
        val perFrame = listOf("dispatchDraw", "onDraw", "drawCapsule", "drawClassic", "drawTicks", "drawFluentTicks",
            "drawHighlight", "drawIcon", "applyVisuals", "onFrame", "setFill", "setPress", "updateDrag")
        for (name in perFrame) {
            val bodies = MotionSource.functions(slider, name)
            assertTrue("$name not found", bodies.isNotEmpty())
            bodies.forEach { assertNoAllocation(name, it) }
        }
        // The frame callback is one long-lived field, not a lambda per post.
        assertEquals(1, Regex("FrameCallback \\{").findAll(slider).count())
        assertTrue(slider.contains("postFrameCallback(frameCallback)"))
    }

    @Test fun hdrTypesStayInTheApi34IsolationClass() {
        for (token in listOf("Color.pack", "ColorSpace", "hdrSdrRatio", "isHdrSdrRatioAvailable", "longArrayOf")) {
            assertFalse("$token outside the isolation class", slider.contains(token) || policy.contains(token))
            assertTrue(hdr.contains(token))
        }
        assertTrue(hdr.contains("@RequiresApi(34)"))
        // Every use is behind a lint-visible SDK check.
        Regex("SliderHdrHighlightApi34\\.").findAll(slider).forEach { match ->
            val before = slider.substring(maxOf(0, match.range.first - 200), match.range.first)
            assertTrue("unguarded HDR call", before.contains("Build.VERSION.SDK_INT >= 34"))
        }
    }

    @Test fun noReflectionAndPolicyHasNoAndroidGraphics() {
        val raw = MotionSource.file("LumenSlider") + MotionSource.file("LumenSliderPolicy") + MotionSource.file("LumenSliderOptions")
        for (token in listOf("Class.forName", "getDeclaredField", "getDeclaredMethod", "java.lang.reflect")) {
            assertFalse(token, raw.contains(token))
        }
        assertFalse(MotionSource.file("LumenSliderPolicy").contains("import android."))
    }

    @Test fun engineExcludesTheSliderFromElasticAndPaging() {
        val elastic = MotionSource.code(MotionSource.file("ElasticInteractionController"))
        assertTrue(elastic.contains("view is AbsSeekBar ||\n            view is LumenSlider ||") ||
            Regex("view is AbsSeekBar \\|\\|\\s+view is LumenSlider").containsMatchIn(elastic))
        val pager = MotionSource.code(MotionSource.file("LumenPagePager"))
        assertTrue(pager.contains("view is SeekBar || view is LumenSlider"))
    }

    @Test fun gestureYieldsToScrollingParentsUntilHorizontal() {
        // DOWN must not lock the parent: only a decided horizontal drag disallows interception.
        val down = slider.substringAfter("MotionEvent.ACTION_DOWN ->").substringBefore("MotionEvent.ACTION_MOVE ->")
        assertFalse(down.contains("requestDisallowInterceptTouchEvent(true)"))
        assertTrue(MotionSource.functions(slider, "startDrag").single().contains("requestDisallowInterceptTouchEvent(true)"))
        assertTrue(slider.contains("shouldDelayChildPressedState()"))
    }

    @Test fun prismLensNeverSamplesTheBackgroundAndControllersAreClosed() {
        // A sampled glass needs an opaque source; the slider's track is mostly transparent, so sampling it shows
        // the glass's opaque fallback (a white block in light theme). The lens draws the track itself instead.
        assertFalse(slider.contains("LumenPrismGlass") || slider.contains("LumenSurfaceSession"))
        assertTrue(MotionSource.functions(slider, "drawPrism").single().contains("trackLayer.drawLens(this)"))
        val release = MotionSource.functions(slider, "releaseMaterials").single()
        assertTrue(release.contains("fluent?.close()"))
        assertTrue(MotionSource.functions(slider, "dispose").single().contains("releaseMaterials()"))
        assertTrue(MotionSource.functions(slider, "dispose").single().contains("removeFrame()"))
        // A host session is never closed by the slider.
        assertFalse(slider.contains("session?.close()") || slider.contains("session.close()"))
    }
}
