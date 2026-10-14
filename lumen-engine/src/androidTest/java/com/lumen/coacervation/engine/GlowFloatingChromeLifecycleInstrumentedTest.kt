package com.lumen.coacervation.engine

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.glow.GlowBackdropTarget
import com.lumen.coacervation.engine.glow.GlowEngine
import com.lumen.coacervation.engine.glow.GlowEngineCallbacks
import com.lumen.coacervation.engine.glow.GlowFloatingChrome
import com.lumen.coacervation.engine.glow.GlowScrollEdge
import com.lumen.coacervation.engine.model.SkinId
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real View child/listener ownership without creating a renderer or persistent settings. */
@RunWith(AndroidJUnit4::class)
class GlowFloatingChromeLifecycleInstrumentedTest {
    private class Host(context: Context) : View(context) {
        val layoutListeners = linkedSetOf<OnLayoutChangeListener>()
        override fun addOnLayoutChangeListener(listener: OnLayoutChangeListener) {
            super.addOnLayoutChangeListener(listener)
            layoutListeners.add(listener)
        }
        override fun removeOnLayoutChangeListener(listener: OnLayoutChangeListener) {
            super.removeOnLayoutChangeListener(listener)
            layoutListeners.remove(listener)
        }
    }

    private class Engine(private val source: GlowBackdropTarget) : GlowEngine {
        val clearedHosts = arrayListOf<View>()
        override val skin = SkinId.MATERIAL_YOU
        override fun bindRoot(root: View, callbacks: GlowEngineCallbacks) = true
        override fun surface(fallbackColor: Int, radiusDp: Float, role: SurfaceRole) = ColorDrawable(fallbackColor)
        override fun notifyPositionChanged() = Unit
        override fun onStart() = Unit
        override fun onStop() = Unit
        override fun onTrimMemory(level: Int) = Unit
        override fun onLowMemory() = Unit
        override fun close() = Unit
        override fun setSurfaceBackdrop(host: View, target: GlowBackdropTarget?) {
            assertNull(target)
            assertTrue("Withdraw every backdrop while its source can still be captured", source.contentCaptureEnabled)
            clearedHosts.add(host)
        }
    }

    @Test fun reuseRemovesOnlyOwnedEdgesAndHostListenersAndDisposeIsIdempotent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val target = GlowBackdropTarget(context)
            val content = View(context)
            target.addView(content)
            val host = Host(context)
            var unrelatedLayouts = 0
            val unrelated = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> unrelatedLayouts++ }
            host.addOnLayoutChangeListener(unrelated)
            repeat(3) {
                val chrome = GlowFloatingChrome(target, { null }, { 1f })
                chrome.attach(host, GlowScrollEdge.TOP, Color.WHITE) {}
                chrome.attach(host, GlowScrollEdge.TOP, Color.WHITE) {}
                assertEquals(3, target.childCount)
                assertEquals("Repeated registration shares one removable listener", 2, host.layoutListeners.size)
                chrome.dispose()
                assertEquals(1, target.childCount)
                assertSame(content, target.getChildAt(0))
                assertEquals(setOf(unrelated), host.layoutListeners)
                chrome.dispose()
                chrome.attach(host, GlowScrollEdge.BOTTOM, Color.WHITE) {}
                assertEquals(1, target.childCount)
                assertEquals(setOf(unrelated), host.layoutListeners)
            }
            host.layout(0, 0, 80, 40)
            assertTrue("Other layout listeners remain active", unrelatedLayouts > 0)
        }
    }

    @Test fun disposingOneControllerKeepsOtherOwnersAndWithdrawsBackdropsBeforeCaptureStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val target = GlowBackdropTarget(context)
            val host = Host(context)
            val companion = View(context)
            val unrelatedContent = View(context)
            target.addView(unrelatedContent)
            val engine = Engine(target)
            val first = GlowFloatingChrome(target, { engine }, { 1f })
            first.attach(host, GlowScrollEdge.TOP, Color.WHITE, listOf(companion)) {}
            val firstEdges = (1 until target.childCount).map(target::getChildAt)
            val second = GlowFloatingChrome(target, { null }, { 1f })
            second.attach(host, GlowScrollEdge.BOTTOM, Color.WHITE) {}
            val secondEdges = (3 until target.childCount).map(target::getChildAt)
            // A view whose parent changed is outside the controller's hierarchy ownership.
            val movedEdge = firstEdges.first()
            val otherParent = FrameLayout(context)
            target.removeView(movedEdge)
            otherParent.addView(movedEdge)
            target.contentCaptureEnabled = true
            first.dispose()
            assertFalse(target.contentCaptureEnabled)
            assertEquals(listOf(host, companion), engine.clearedHosts)
            assertSame(otherParent, movedEdge.parent)
            assertNull(firstEdges.last().parent)
            assertEquals(3, target.childCount)
            assertSame(unrelatedContent, target.getChildAt(0))
            secondEdges.forEach { assertSame(target, it.parent) }
            assertEquals("Keep the second controller's host listener", 1, host.layoutListeners.size)
            first.dispose()
            second.dispose()
            assertEquals(1, target.childCount)
            assertTrue(host.layoutListeners.isEmpty())
        }
    }
}
