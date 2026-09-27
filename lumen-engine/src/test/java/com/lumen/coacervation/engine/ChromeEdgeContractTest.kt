package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮栏不再只认"顶栏一条 + 底栏一条"（适配标准 §15.6）：
 * 不贴滚动边缘的悬浮表面（侧边栏、悬浮按钮）可以登记；同一条边的多条栏按并集溶解。
 */
class ChromeEdgeContractTest {
    private val chrome = SourceContract.read("glow/GlowFloatingChrome.kt")

    @Test fun surfacesMayHaveNoScrollEdge() {
        assertTrue(chrome.contains("        val edge: GlowScrollEdge?,"))
        assertTrue(chrome.contains("fun attach(\n        host: View,\n        edge: GlowScrollEdge?,"))
    }

    @Test fun barsSharingAnEdgeDissolveAsOneUnion() {
        val geometry = chrome.after("private fun updateGeometry()").before("private fun updateCoverage()")
        assertTrue(geometry.contains("for ((edge, view) in edgeViews) {"))
        assertTrue(geometry.contains("minOf(top, surface.region.top)"))
        assertTrue(geometry.contains("maxOf(bottom, surface.region.bottom)"))
        // 逐条栏直接写溶解层会让最后一条覆盖前面的。
        assertFalse(geometry.contains("edgeViews[surface.edge]?.setCapsule"))
    }
}
