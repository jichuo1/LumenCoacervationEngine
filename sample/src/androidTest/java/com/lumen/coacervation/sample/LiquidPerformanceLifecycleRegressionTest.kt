package com.lumen.coacervation.sample

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real renderer lifecycle with deterministic thermal and allocation boundaries. */
@SdkSuppress(minSdkVersion = 31)
@RunWith(AndroidJUnit4::class)
class LiquidPerformanceLifecycleRegressionTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun read(owner: Any, name: String): Any? = field(owner, name).get(owner)
    private fun call(owner: Any, name: String) = owner.javaClass.getMethod(name).invoke(owner)

    private class Thermal(var status: Int) {
        var starts = 0
        var stops = 0
        var closes = 0
        fun monitor(): Any {
            val type = Class.forName("com.lumen.coacervation.engine.liquid.LiquidThermalMonitor")
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.name) {
                    "getCurrentThermalStatus" -> status
                    "start" -> { starts++; null }
                    "stop" -> { stops++; null }
                    "close" -> { closes++; null }
                    else -> error("Unexpected thermal method: ${method.name}")
                }
            }
        }
    }

    private fun withRenderer(allocator: (Int, Int) -> Bitmap, test: (Activity, Any, Any, Thermal) -> Unit) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val preferences = activity.getSharedPreferences(LumenEngine.config.storage.realtimeCapturePreferences, 0)
                val key = "fullscreen_realtime_capture_enabled"
                val hadKey = preferences.contains(key)
                val enabled = preferences.getBoolean(key, false)
                assertTrue(preferences.edit().putBoolean(key, true).commit())
                var renderer: Any? = null
                try {
                    val type = Class.forName("com.lumen.coacervation.engine.liquid.LiquidActivityRenderer")
                    renderer = type.declaredConstructors.single {
                        it.parameterCount == 4 && it.parameterTypes[0] == Activity::class.java
                    }.newInstance(activity, LumenPalette.neutral(false), LumenEffectTuning.DEFAULT, allocator)
                    assertEquals("REALTIME_CAPTURE", read(renderer, "effectProfile").toString())
                    val performance = requireNotNull(read(renderer, "performanceController"))
                    val thermal = Thermal(4)
                    field(performance, "thermalMonitor").set(performance, thermal.monitor())
                    test(activity, renderer, performance, thermal)
                } finally {
                    renderer?.let { call(it, "close") }
                    val editor = preferences.edit()
                    if (hadKey) editor.putBoolean(key, enabled) else editor.remove(key)
                    assertTrue(editor.commit())
                }
            }
        }
    }

    @Test fun startAndResumeApplyCurrentThermalBudgetWithoutAChangeCallbackOrBoundRoot() {
        withRenderer({ w, h -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }) { _, renderer, performance, thermal ->
            call(renderer, "onActivityStarted")
            val criticalBudget = read(renderer, "realtimeSamplePixelBudget") as Long
            assertTrue("Initial thermal status must reduce the 1M pixel budget", criticalBudget in 1 until 600_000)
            val duplicate = performance.javaClass.getDeclaredMethod("handleThermalStatusChanged", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            duplicate.invoke(performance, 4)
            assertEquals(criticalBudget, read(renderer, "realtimeSamplePixelBudget"))
            call(renderer, "onActivityStopped")
            thermal.status = 0
            call(renderer, "onActivityStarted")
            assertEquals(1_000_000L, read(renderer, "realtimeSamplePixelBudget"))
            call(renderer, "onActivityStopped")
            thermal.status = 3
            call(renderer, "onActivityStarted")
            val severeBudget = read(renderer, "realtimeSamplePixelBudget") as Long
            assertTrue(severeBudget > criticalBudget && severeBudget < 1_000_000L)
            assertEquals(3, thermal.starts)
            assertEquals(2, thermal.stops)
        }
    }

    @Test fun partialBufferAllocationFailureSuspendsResourcesAndRestoresOwnedWindowRequest() {
        var calls = 0
        var first: Bitmap? = null
        withRenderer({ w, h ->
            if (++calls == 2) throw IllegalStateException("Injected second-buffer allocation failure")
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { first = it }
        }) { activity, renderer, performance, thermal ->
            val originalRate = activity.window.attributes.preferredRefreshRate
            val originalMode = activity.window.attributes.preferredDisplayModeId
            call(renderer, "onActivityStarted")
            val refresh = requireNotNull(read(renderer, "refreshRate"))
            refresh.javaClass.getMethod("configure", View::class.java, Int::class.javaPrimitiveType)
                .invoke(refresh, activity.window.decorView, 4)
            val root = View(activity).apply { layout(0, 0, 400, 300) }
            val allocate = renderer.javaClass.getDeclaredMethod("ensureRealtimeCaptureSources", View::class.java)
                .apply { isAccessible = true }
            assertNull(allocate.invoke(renderer, root))
            assertTrue(requireNotNull(first).isRecycled)
            assertEquals(true, read(renderer, "realtimeCaptureSuspended"))
            assertEquals(false, read(performance, "active"))
            assertNull(read(performance, "hintSession"))
            assertEquals(1, thermal.stops)
            assertEquals(originalRate, activity.window.attributes.preferredRefreshRate, 0f)
            assertEquals(originalMode, activity.window.attributes.preferredDisplayModeId)
            call(renderer, "onActivityStarted")
            assertEquals("A permanently suspended renderer must not reopen thermal resources", 1, thermal.starts)
        }
    }
}
