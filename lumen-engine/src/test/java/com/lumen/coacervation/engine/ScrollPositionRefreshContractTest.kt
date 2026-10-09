package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hardware draw ordering needs Android coverage; these contracts protect ownership and scope. */
class ScrollPositionRefreshContractTest {
    @Test fun publicBridgeKeepsClosedSessionsAndFallbackRouting() {
        val delegate = SourceContract.read("host/LumenActivityDelegate.kt")
            .after("public fun notifyScrollPositionChanged(scrollHost: View)").before("// ---------------- 表面")
        assertTrue(delegate.contains("if (!lifecycleEnded) session?.notifyScrollPositionChanged(scrollHost)"))
        val session = SourceContract.read("runtime/ActivitySkinSession.kt")
            .after("fun notifyScrollPositionChanged(scrollHost: View)").before("fun notifyGestureActive(")
        assertTrue(session.contains("if (!isClosed) activeEngine.notifyScrollPositionChanged(scrollHost)"))
        assertTrue(SourceContract.read("glow/GlowEngine.kt")
            .contains("fun notifyScrollPositionChanged(scrollHost: View) = Unit"))
    }

    @Test fun liquidRefreshesOnlyVisibleStaleDescendantsWithoutConsumingAnimationBatch() {
        val renderer = SourceContract.read("liquid/LiquidActivityRenderer.kt")
        val scroll = renderer.after("override fun notifyScrollPositionChanged(scrollHost: View)")
            .before("private fun invalidateMovedSurfaces()")
        assertTrue(scroll.contains("val root = boundRoot ?: return"))
        assertTrue(scroll.contains("closed || !activityVisible || !scrollHost.isAttachedToWindow || !scrollHost.isShown || surfaceViews.isEmpty()"))
        assertTrue(scroll.contains("if (!refreshWindows.containsKey(windowRoot)) return"))
        assertTrue(scroll.contains("view.rootView !== windowRoot"))
        assertTrue(scroll.contains("ScrollSurfaceScope.contains(view, scrollHost)"))
        assertTrue(scroll.contains("val visible = isSurfacePotentiallyVisible(view)"))
        assertTrue(scroll.contains("shouldRefresh(visible = false, originChanged = false, contentChanged = false)"))
        assertTrue(scroll.contains("shouldRefresh(visible = true, originChanged = originChanged, contentChanged = false)"))
        assertTrue(scroll.contains("!footprint.matchesOrigin("))
        assertTrue(scroll.contains("SamplingMatrixMath.equal(footprint.screenTransform, refreshSurfaceTransform)"))
        assertTrue(scroll.contains("view.invalidate()"))
        assertTrue(scroll.contains("windowRoot === root.rootView"))
        assertTrue(scroll.contains("lastContentShiftNanos = System.nanoTime()"))
        assertTrue(scroll.contains("suppressionFromMorphOnly = false"))
        assertTrue(scroll.contains("suppressRealtimeSamplingWhileScrolling()"))
        assertFalse(scroll.contains("flushSurfaceRefresh("))
        assertFalse(scroll.contains("queueSurfaceRefresh("))
        assertFalse(scroll.contains(".take()"))
        assertFalse(scroll.contains("footprint.update("))
        assertFalse(scroll.contains("footprint.hasTransform ="))
        assertFalse(scroll.contains("PixelCopy.request("))
        val animation = renderer.after("override fun notifyPositionChanged()")
            .before("override fun notifyScrollPositionChanged(")
        assertTrue(animation.contains("queueSurfaceRefresh(contentChanged = false)"))
        assertFalse(animation.contains("suppressRealtimeSamplingWhileScrolling()"))
    }

    @Test fun onlyWindowScrollListenerMayFlushLateAndDrawObserverOnlyResetsPhase() {
        val renderer = SourceContract.read("liquid/LiquidActivityRenderer.kt")
        val registration = renderer.after("private fun registerRefreshWindow(").before("private fun removeRefreshWindow(")
        assertTrue(registration.contains("state?.batch?.beforeDraw()"))
        assertTrue(registration.contains("if (state.batch.isAfterPreDraw) flushSurfaceRefresh(root)"))
        assertTrue(registration.contains("observer.addOnDrawListener(draw)"))
        val draw = registration.after("val draw = ViewTreeObserver.OnDrawListener").before("val state = LiquidWindowRefresh(")
        assertTrue(draw.contains(".drawn()"))
        assertFalse(draw.contains("invalidate("))
        assertFalse(draw.contains("flushSurfaceRefresh("))
        val remove = renderer.after("private fun removeRefreshWindow(").before("private fun queueSurfaceRefresh(")
        assertTrue(remove.contains("removeOnDrawListener(state.draw)"))
        assertTrue(remove.contains("runCatching { it.removeOnDrawListener(state.draw) }.onFailure"))
        assertTrue(remove.contains("mainHandler.post"))
        assertTrue(remove.contains("state.observer.get()?.takeIf { observer -> observer.isAlive }"))
        assertTrue(remove.contains("runCatching { observer.removeOnDrawListener(state.draw) }"))
        val rootScroll = renderer.after("private fun invalidateMovedSurfaces()").before("private fun suppressRealtimeSamplingWhileScrolling()")
        assertTrue(rootScroll.contains("queueSurfaceRefresh(contentChanged = false)"))
        assertFalse(rootScroll.contains("flushSurfaceRefresh("))
    }

    @Test fun frostedReusesScopedOriginRefreshWithoutRebuildingBackdropOrTakingWindowBatch() {
        val renderer = SourceContract.read("material/FrostedMaterialRenderer.kt")
        val scroll = renderer.after("override fun notifyScrollPositionChanged(scrollHost: View)")
            .before("private fun onPositionChanged()")
        assertTrue(scroll.contains("if (!lifecycle.canWork || !scrollHost.isAttachedToWindow) return"))
        assertTrue(scroll.contains("flushPositionChanges(scrollHost.rootView, scrollHost)"))
        assertFalse(scroll.contains("requestBackdrop("))
        assertFalse(scroll.contains("batch."))
        val flush = renderer.after("private fun flushPositionChanges(").before("fun releaseMemory()")
        assertTrue(flush.contains("ScrollSurfaceScope.contains(view, scrollHost)"))
        assertTrue(flush.contains("SamplingMatrixMath.equal(position.target, movedTransform)"))
    }
}
