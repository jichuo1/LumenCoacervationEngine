package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidSurfaceRefreshState
import org.junit.Assert.*
import org.junit.Test
import com.lumen.coacervation.engine.contract.SourceContract

/**
 * 显式位移通知的接线护栏 + 刷新状态机；不是 RenderThread 时序测试。
 * 来源工程里还断言了设置页翻页器"平移与可见性都应用后才通知"——那是宿主义务，见 INTEGRATION_STANDARD.md §4。
 */
class PositionRefreshTest {
    private fun source(relative: String): String = SourceContract.read("$relative.kt")

    private fun function(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing $signature" }
        val body = source.indexOf('{', start)
        var depth = 1
        var end = body + 1
        while (depth > 0 && end < source.length) {
            when (source[end++]) { '{' -> depth++; '}' -> depth-- }
        }
        check(depth == 0) { "Unclosed $signature" }
        return source.substring(body, end)
    }

    @Test fun activityAndSessionKeepMaterialAndClosedLifecyclesOutOfTheRenderer() {
        val delegate = function(source("host/LumenActivityDelegate"), "public fun notifyPositionChanged()")
        assertTrue(delegate.contains("if (!lifecycleEnded) session?.notifyPositionChanged()"))
        // 会话只把位移通知转给**当前在画**的引擎，且关闭后不再转发。
        val sessionSource = source("runtime/ActivitySkinSession")
        val session = function(sessionSource, "fun notifyPositionChanged()")
        assertTrue(session.contains("if (!isClosed) activeEngine.notifyPositionChanged()"))
        assertTrue("Liquid 只在实际生效时接收；失败回落后转给柔光",
            sessionSource.contains("liquidRenderer?.takeIf { effectiveSkin == SkinId.LIQUID } ?: materialRenderer"))
    }

    @Test fun positionBridgeReusesOriginComparisonWithoutRequestingPixelCopyOrRebuildingBackgrounds() {
        val renderer = source("liquid/LiquidActivityRenderer")
        val notify = function(renderer, "fun notifyPositionChanged()")
        assertTrue(notify.contains("queueSurfaceRefresh(contentChanged = false)"))
        // 显式变换回调不得直接抑制采样：按下缩放绕中心进行、表面原点不变，
        // 此刻换底图只是白闪一次（2026-09-21 真机实证：点击也会出现高光重载）。
        // 真实位移由 flushSurfaceRefresh 按原点变化门控后再触发抑制。
        assertFalse(notify.contains("suppressRealtimeSamplingWhileScrolling"))
        assertFalse(notify.contains("PixelCopy"))
        assertFalse(notify.contains("rebuildBackdrop("))
        val moved = function(renderer, "private fun invalidateMovedSurfaces()")
        assertTrue(moved.contains("queueSurfaceRefresh(contentChanged = false)"))
        val flush = function(renderer, "private fun flushSurfaceRefresh(windowRoot: View)")
        assertTrue(flush.contains("if (closed) return"))
        assertTrue(flush.contains("isSurfacePotentiallyVisible(view)"))
        assertTrue(flush.contains("!entry.value.matchesOrigin("))
        assertTrue(flush.contains("suppressRealtimeSamplingWhileScrolling()"))
        // 2026-09-20：回弹边界环移除，flush 不再刷新 stretch viewport 的边界记录。
        assertFalse(flush.contains("stretchViewports"))
        assertFalse(moved.contains("invalidateRegisteredSurfaces("))
        assertFalse(moved.contains("PixelCopy"))
        assertFalse(moved.contains("rebuildBackdrop("))
        // Ordinary scrolling retains the same origin-refresh path.
        assertTrue(renderer.contains("addOnScrollChangedListener(scrollListener)"))
    }

    @Test fun contentRefreshTriggersTraversalThroughOneVisibleSurfaceNotTheWholeWindow() {
        val renderer = source("liquid/LiquidActivityRenderer")
        val queue = function(renderer, "private fun queueSurfaceRefresh(contentChanged: Boolean, captureOnly: Boolean = false)")
        // 整窗 root.invalidate() 会把每个 View 的 display list 标脏重录；
        // 滚动期 PixelCopy 完成与回弹期强度步进每秒数十次走到这里。
        assertFalse(queue.contains("root.invalidate()"))
        assertTrue(queue.contains("triggerSurfaceFrame(root, captureOnly)"))
        val trigger = function(renderer, "private fun triggerSurfaceFrame(windowRoot: View, captureOnly: Boolean)")
        assertTrue(trigger.contains("view.isShown"))
        assertTrue(trigger.contains("view.invalidate()"))
        // 损伤域必须收缩到表面矩形：flush 内的逐表面 invalidate 仍是唯一扩散点。
        val flush = function(renderer, "private fun flushSurfaceRefresh(windowRoot: View)")
        assertTrue(flush.contains("view.invalidate()"))
    }

    @Test fun originRefreshKeepsStationaryControlsIdleAndRefreshesHiddenPagesOnlyOnReentry() {
        val movingPage = LiquidSurfaceRefreshState()
        val stationaryToolbar = LiquidSurfaceRefreshState()
        val hiddenPage = LiquidSurfaceRefreshState()
        repeat(120) {
            assertTrue(movingPage.shouldRefresh(visible = true, originChanged = true, contentChanged = false))
            assertFalse(stationaryToolbar.shouldRefresh(visible = true, originChanged = false, contentChanged = false))
            assertFalse(hiddenPage.shouldRefresh(visible = false, originChanged = true, contentChanged = false))
        }
        assertTrue(hiddenPage.shouldRefresh(visible = true, originChanged = false, contentChanged = false))
        assertFalse(hiddenPage.shouldRefresh(visible = true, originChanged = false, contentChanged = false))
    }
}
