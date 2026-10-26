package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.MotionSource
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Material opacity in lumen-motion (MATERIAL_OPACITY.md §7.3, §4.7): frame paths stay allocation-free. */
class MaterialOpacityMotionContractTest {
    private val forbidden = listOf("Paint(", "Matrix(", "Path()", "RectF(", "LinearGradient(", "RadialGradient(",
        "listOf(", "arrayOf(", "LumenFluentControl(", "installMaterials", "installGroove", "configure(")

    private fun assertCheap(name: String, body: String) {
        for (word in forbidden) assertFalse("$name contains $word", body.contains(word))
    }

    @Test fun scopeFramesOnlyWriteFloats() {
        val scope = MotionSource.file("LumenMaterialOpacityScope")
        assertCheap("onFrame", scope.after("private fun onFrame(now: Long)").before("private fun apply()"))
        val apply = scope.after("private fun apply()").before("private fun stop()")
        assertCheap("apply", apply)
        assertTrue(apply.contains("for (i in 0 until targets.size) targets[i].setMaterialOpacity(scale * bases[i])"))
        // A reversal continues from the current position and velocity (ARCHITECTURE.md §11.2).
        val animate = scope.after("public fun animateTo(value: Float)").before("override fun setMaterialOpacity(value: Float)")
        assertTrue(animate.contains("LumenSpring(current, next, velocity, stiffness, dampingRatio)"))
        assertTrue(animate.contains("if (!ValueAnimator.areAnimatorsEnabled()) { set(next); return }"))
    }

    @Test fun sliderOpacityLeavesTheValueAlone() {
        val slider = MotionSource.file("LumenSlider")
        val apply = slider.after("private fun applyMaterialOpacity()").before("/** 程序设置值")
        assertCheap("slider", apply)
        // Fill, ticks, handle and HDR highlight are content: only the groove and thumb material are touched.
        for (content in listOf("fillPaint", "tickPaint", "handlePaint", "highlightPaint")) assertFalse(apply.contains(content))
        assertTrue(apply.contains("TRACK_FLOOR + (1f - TRACK_FLOOR) * p"))
    }

    @Test fun barsOnlyThinTheirBackground() {
        for (name in listOf("LumenNavigationBar", "LumenSegmentScrubBar")) {
            val setter = MotionSource.file(name).after("override fun setMaterialOpacity(value: Float)").before("override fun materialOpacity()")
            assertCheap(name, setter)
            assertTrue(name, setter.contains("LumenMaterialOpacity.set(background, next)"))
            assertFalse(name, setter.contains("selection") || setter.contains("thumb"))
        }
    }
}
