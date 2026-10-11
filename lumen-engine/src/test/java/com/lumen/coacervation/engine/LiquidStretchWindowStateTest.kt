package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.liquid.LiquidStretchWindowState
import com.lumen.coacervation.engine.contract.SourceContract
import com.lumen.coacervation.engine.contract.after
import com.lumen.coacervation.engine.contract.before
import org.junit.Assert.*
import org.junit.Test

class LiquidStretchWindowStateTest {
    @Test fun dialogStepsAndRestingWindowsAreIndependent() {
        val main = LiquidStretchWindowState()
        val dialog = LiquidStretchWindowState()
        assertTrue(dialog.update(.1f, 1.5f, -1f))
        assertEquals(1f, main.opticalIntensity, 0f)
        assertEquals(0f, main.edgeDirection, 0f)
        assertTrue(main.update(.05f, 1.2f, 1f))
        assertTrue(dialog.update(0f, 1f, 0f))
        assertEquals(1.2f, main.opticalIntensity, 0f)
        assertEquals(1f, main.edgeDirection, 0f)
    }

    @Test fun quantizedRepeatsAreSkippedButTerminalZeroIsAlwaysPublished() {
        val state = LiquidStretchWindowState()
        assertFalse(state.update(0f, 1f, 0f))
        assertTrue(state.update(.1f, 1.5f, -1f))
        assertFalse(state.update(.1001f, 1.501f, -1f))
        assertTrue(state.update(.1001f, 1.501f, 1f))
        assertTrue(state.update(0f, 1f, 0f))
        assertEquals(0f, state.distance, 0f)
        assertEquals(1f, state.opticalIntensity, 0f)
    }

    @Test fun onlyTheOwnerWindowRefreshesAndOnlyMainStretchSuppressesCapture() {
        val renderer = SourceContract.read("liquid/LiquidActivityRenderer.kt")
        val handler = renderer.after("private fun onStretchDistanceChanged(").before("override fun onTrimMemory(")
        assertTrue(handler.contains("stretchWindows.update(windowRoot, distance, next, nextDir)"))
        val main = handler.after("if (windowRoot === boundRoot?.rootView) {").before("// 回弹不切换采样路径")
        assertTrue(main.contains("suppressRealtimeSamplingWhileScrolling()"))
        assertTrue(main.contains("lastContentShiftNanos = System.nanoTime()"))
        assertTrue(handler.contains("refreshWindows[windowRoot]?.batch?.mark(contentChanged = true)"))
        assertTrue(handler.contains("flushSurfaceRefresh(windowRoot)"))
        assertFalse(handler.contains("invalidateRegisteredSurfaces()"))
        val install = SourceContract.read("glow/GlowEngine.kt").after("internal fun GlowEngine.installStretchViewport(")
        assertTrue(install.contains("onStretchDistance(distance, edge, scrollTarget)"))
    }
}
