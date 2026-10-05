package com.lumen.coacervation.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.before
import com.lumen.coacervation.engine.contract.after

/**
 * 2026-09-23 悬浮栏可读性改造 A 期的装配约束：生命周期与底图同源。
 * 来源工程里另有两条断言设置首页的层级（内容容器只装 pager、栏是它的兄弟）——那是宿主义务，
 * 见 docs/INTEGRATION_STANDARD.md §6「悬浮栏」。
 */
class GlowFloatingChromeWiringTest {
    private fun source(relative: String): String = SourceContract.read(relative)

    @Test fun chromeRemovesEveryObserverItAdds() {
        val chrome = source("glow/GlowFloatingChrome.kt")
        for (kind in listOf("ScrollChangedListener", "GlobalLayoutListener", "PreDrawListener")) {
            assertEquals(kind, 1, Regex("addOn$kind\\(").findAll(chrome).count())
            assertEquals(kind, 1, Regex("removeOn$kind\\(").findAll(chrome).count())
        }
        val dispose = chrome.after("fun dispose() {").before("\n    }\n")
        assertTrue(dispose.contains("probe.close()"))
        assertTrue(dispose.contains("setSurfaceLegibility(surface.host, null)"))
        assertTrue(dispose.contains("removeCallbacks(probeRunnable)"))
    }

    @Test fun dissolveDrawsTheVisibleRootBitmapNotTheOpticalCopy() {
        val source = source("liquid/LiquidBackdropSource.kt")
        val presentation = source.after("fun drawPresentationRegion(").before("\n    }\n")
        // 溶解区必须与根背景逐像素一致，独立的呈现 Shader 只采显示底图。
        val shader = source.after("private val presentationShader by lazy").before("private val presentationMatrix")
        assertTrue(shader.contains("BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)"))
        assertFalse(presentation.contains("opticalBitmap"))
        assertFalse(presentation.contains("bitmapShader.setLocalMatrix"))
        assertFalse(presentation.contains("opticalRegionShader"))
        assertFalse(presentation.contains("suppressionShader"))
        assertTrue(presentation.contains("PorterDuff.Mode.DST_IN"))
    }

    @Test fun everyBackdropReplacementAdvancesTheGeneration() {
        val renderer = source("liquid/LiquidActivityRenderer.kt")
        val assignments = Regex("\\bbackdropSource = (created|source)\\b").findAll(renderer).count()
        assertEquals(2, assignments)
        // 两次换源 + 同尺寸位图的窗口映射更新（updateFullSize）。
        assertEquals(3, Regex("backdropGeneration\\+\\+").findAll(renderer).count())
        assertTrue(renderer.after("existing.updateFullSize(width, height)").trimStart()
            .startsWith("backdropGeneration++"))
    }

    @Test fun softwareCanvasDrawsNeverRefreshTheRecordedFootprint() {
        // 探针在软件画布上录内容：若表面也登记，footprint 的"上次录制位置"被刷新却没有重录，
        // 滚动后该表面不再失效，玻璃里的背景停在旧位置（Phase A review 发现）。
        val drawables = source("liquid/LiquidSurfaceDrawables.kt")
        assertTrue(drawables.contains("if (canvas.isHardwareAccelerated) {\n" +
            "                renderer.registerSurfaceView("))
        assertEquals(1, Regex("registerSurfaceView\\(").findAll(drawables).count())
    }

    @Test fun legibilityOnlyThickensFloatingSurfaces() {
        val renderer = source("liquid/LiquidActivityRenderer.kt")
        assertTrue(renderer.contains(
            "legibility = if (role == SurfaceRole.FLOATING && host != null) surfaceLegibility[host] else null"))
        assertTrue(renderer.contains("LiquidLegibilityTuning.tintAlpha(baseFraction, legibility.boost)"))
        // 光学参数与绘制同一条映射，策略算出的补偿与实际加厚一致。
        assertTrue(renderer.contains("maxTintAlpha = LiquidLegibilityTuning.ceiling(base)"))
    }

    /** 顶栏胶囊本体没有文字：补偿只加在圆按钮上，本体保持清透；底栏的标签压在玻璃上，照旧加厚。 */
    @Test fun topCapsuleBodyStaysClearWhileItsButtonsCarryCompensation() {
        val chrome = SourceContract.read("glow/GlowFloatingChrome.kt")
        assertTrue(chrome.contains("glow?.setSurfaceLegibility(surface.host, value.takeIf { surface.thickenHost })"))
        assertTrue(chrome.contains("surface.companions.forEach { glow?.setSurfaceLegibility(it, value) }"))
    }
}
