package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.material.FrostedMotionSurfaceAlpha
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before

class FrostedMotionSurfaceIntegrationTest {
    private fun source(relative: String): String = SourceContract.read("$relative.kt")

    @Test fun ordinaryDiagnosticEntryKeepsItsCallerArgbInSampledAndFallbackDrawing() {
        // 来源工程取诊断入口的两档遮罩透明度；独立引擎改为覆盖一组有代表性的调用方透明度。
        for (callerAlpha in listOf(0x14, 0x2A, 0x5C, 0x80, 0xC0, 0xFF)) {
            val callerColor = (callerAlpha shl 24) or 0x00909090
            for (drawableAlpha in listOf(0, 64, 128, 255)) {
                val expected = callerAlpha * drawableAlpha / 255
                val frame = FrostedMotionSurfaceAlpha.frameAlpha(callerColor, drawableAlpha)
                // The fallback fill uses frame directly; it must not become the Drawable's full alpha.
                assertEquals(expected, frame)
                assertTrue(frame <= callerAlpha)
                for (tint in listOf(216, 218, 255)) {
                    val overlay = frame * tint / 255
                    val sample = FrostedMotionSurfaceAlpha.sampleAlpha(frame, overlay)
                    assertEquals(expected.toFloat(), overlay + sample * (1f - overlay / 255f), .51f)
                }
            }
        }
        val draw = source("material/FrostedMaterialRenderer")
            .after("private class ModernSurfaceDrawable(")
            .after("override fun draw(canvas: Canvas)").before("override fun setAlpha")
        assertTrue(draw.contains("FrostedMotionSurfaceAlpha.frameAlpha(drawColor, drawingAlpha)"))
        assertFalse(draw.contains("if (motionProvider != null) drawingAlpha"))
    }

    @Test fun opaqueCardsKeepTheirPreviousAlphaWhileTransparentArgbRemainsTransparent() {
        for (drawableAlpha in 0..255) {
            assertEquals(drawableAlpha, FrostedMotionSurfaceAlpha.frameAlpha(0xFFFAFAFA.toInt(), drawableAlpha))
            assertEquals(drawableAlpha, FrostedMotionSurfaceAlpha.frameAlpha(0xFF17191B.toInt(), drawableAlpha))
            assertEquals(0, FrostedMotionSurfaceAlpha.frameAlpha(0x00FAFAFA, drawableAlpha))
        }
        assertEquals(255, FrostedMotionSurfaceAlpha.frameAlpha(0xFFFFFFFF.toInt(), 300))
        assertEquals(0, FrostedMotionSurfaceAlpha.frameAlpha(0xFFFFFFFF.toInt(), -1))
    }

    @Test fun tintAndSampleCompositePreserveHostOpacityIncludingTransparentDiagnosticOrigins() {
        for (frame in 0..255) for (tint in listOf(0, 64, 180, 216, 255)) {
            val overlay = frame * tint / 255
            val sample = FrostedMotionSurfaceAlpha.sampleAlpha(frame, overlay)
            val combined = overlay + sample * (1f - overlay / 255f)
            assertEquals(frame.toFloat(), combined, .51f)
            assertTrue(sample in 0..255)
        }
        assertEquals(0, FrostedMotionSurfaceAlpha.sampleAlpha(0, 0))
        assertEquals(0, FrostedMotionSurfaceAlpha.sampleAlpha(255, 255))
        assertEquals(255, FrostedMotionSurfaceAlpha.sampleAlpha(255, 216))
    }

    @Test fun frostedDrawableUsesLiveMotionGeometryAndNeverSubstitutesFullBoundsForAnEmptyFrame() {
        val renderer = source("material/FrostedMaterialRenderer")
        val drawable = renderer.after("private class ModernSurfaceDrawable(")
            .before("internal object FrostedMotionSurfaceAlpha")
        val draw = drawable.after("override fun draw(canvas: Canvas)").before("override fun setAlpha")
        assertTrue(draw.contains("view as? LiquidMotionSurfaceFrameProvider"))
        assertTrue(draw.contains("motionProvider.copyLiquidMotionBounds(rect)"))
        assertTrue(draw.contains("drawRadius = motionProvider.liquidMotionCornerRadiusPx()"))
        assertTrue(draw.contains("drawColor = motionProvider.liquidMotionFallbackColor()"))
        assertTrue(draw.indexOf("if (rect.isEmpty") > draw.indexOf("motionProvider.copyLiquidMotionBounds(rect)"))
        assertTrue(draw.contains("drawSample(canvas, rect, drawRadius, view, sampleAlpha)"))
        assertTrue(draw.contains("if (motionProvider == null)"))
        assertFalse(draw.contains("LinearGradient("))
        assertFalse(draw.contains("saveLayer("))
    }
}
