package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device lesson (1.4.3 review, OPPO PMA110 / API 37): a ScrollView used as a surface source was recorded with a
 * bare View.draw(), so the sampled backdrop stayed at scroll position 0. Every content recording must go through
 * LocalViewDrawing, which applies the view's own scroll exactly as its parent's display list does.
 * The pixel-level check is sample/src/androidTest/.../ScrollSourceSamplingTest.
 */
class LocalViewDrawingContractTest {
    private val helper = SourceContract.read("geometry/LocalViewDrawing.kt")

    @Test fun ownScrollIsAppliedBeforeDrawAndRestored() {
        val body = helper.after("fun draw(view: View, canvas: Canvas)")
        val translate = body.indexOf("canvas.translate(-x.toFloat(), -y.toFloat())")
        val draw = body.indexOf("view.draw(canvas)", translate)
        assertTrue(translate >= 0 && draw > translate)
        assertTrue(body.contains("canvas.restoreToCount(saved)"))
        assertTrue(body.after("try {").before("} finally").contains("view.draw(canvas)"))
    }

    @Test fun unscrolledViewsKeepThePlainDrawPath() {
        val fast = helper.after("if (x == 0 && y == 0) {").before("val saved")
        assertTrue(fast.contains("view.draw(canvas)"))
        assertTrue(fast.contains("return"))
        assertFalse(helper.after("fun draw(view: View, canvas: Canvas)").contains("Matrix("))
    }

    @Test fun everyContentRecorderUsesLocalSpaceDrawing() {
        val source = SourceContract.read("host/LumenContentSource.kt")
        assertTrue(source.contains("override fun drawContent(canvas: Canvas) = LocalViewDrawing.draw(coordinateView, canvas)"))
        val sampler = SourceContract.read("material/LiveBackdropSampler.kt")
        assertTrue(Regex("if \\(recordContent == null\\) LocalViewDrawing\\.draw\\(content, recording\\)").findAll(sampler).count() == 2)
        assertFalse(sampler.contains("content.draw(recording)"))
        val probe = SourceContract.read("glow/GlowContentProbe.kt")
        assertTrue(probe.contains("LocalViewDrawing.draw(content, recording)"))
        assertFalse(probe.contains("                content.draw(recording)"))
    }
}
