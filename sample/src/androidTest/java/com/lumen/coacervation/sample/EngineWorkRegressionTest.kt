package com.lumen.coacervation.sample

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.effects.*
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.liquid.LiquidWorkDiagnostics
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The same workloads run against the counters-only baseline and the optimized implementation. */
@RunWith(AndroidJUnit4::class)
class EngineWorkRegressionTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun effectUpdatesPreservePixelsAndReportOnlyRequestedWork() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.binding.close(); activity.session.close()
                val view = View(activity).apply { setBackgroundColor(Color.WHITE) }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(320, 240))
                })
                view.layout(0, 0, 320, 240)
                var options = LumenEffectOptions(procedural = LumenProceduralOptions(
                    enabled = true, kind = LumenProceduralKind.PAPER, opacity = .7f, gpuEnabled = false
                ), pauseWhenUnfocused = false)
                val layer = LumenEffectLayer(view, options)
                val image = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                fun pixels(): IntArray {
                    image.eraseColor(Color.TRANSPARENT); view.draw(Canvas(image))
                    return IntArray(320 * 240).also { image.getPixels(it, 0, 320, 0, 0, 320, 240) }
                }
                try {
                    // Visibility is refreshed after the view is attached; a software draw exercises fallback.
                    layer.resume()
                    val before = pixels()
                    val started = System.nanoTime()
                    repeat(1000) { layer.update(options) }
                    val elapsed = System.nanoTime() - started
                    assertArrayEquals(before, pixels())
                    val unchanged = layer.workDiagnostics()
                    assertEquals(unchanged, layer.workDiagnostics())
                    options = options.copy(seed = 7)
                    layer.update(options)
                    val paper = pixels()
                    layer.update(options.copy(seed = 8))
                    assertFalse("A changed seed must change the paper", paper.contentEquals(pixels()))
                    layer.update(options)
                    assertArrayEquals(paper, pixels())
                    options = options.copy(procedural = options.procedural.copy(kind = LumenProceduralKind.FILM))
                    layer.update(options)
                    val film = pixels()
                    repeat(50) { layer.update(options.copy(procedural = options.procedural.copy(opacity = .3f))) }
                    layer.update(options)
                    assertArrayEquals(film, pixels())
                    Log.i("LumenWork", "effect elapsedNanos=$elapsed unchanged=$unchanged final=${layer.workDiagnostics()} paperHash=${paper.contentHashCode()} filmHash=${film.contentHashCode()}")
                    assertEquals(0L, layer.diagnostics().captureRequests)
                } finally { layer.close(); image.recycle() }
            }
        }
    }

    @Test fun dialogPositionWorkloadKeepsOtherWindowsValid() = withLiquid(false) { activity, material, root ->
        lateinit var dialogRoot: FrameLayout
        lateinit var target: View
        lateinit var dialog: Dialog
        WindowRegressionCapture.main {
            dialogRoot = FrameLayout(activity)
            target = View(activity).apply { background = material.cardBackground(Color.WHITE, 8f) }
            dialogRoot.addView(target, FrameLayout.LayoutParams(180, 90))
            dialog = Dialog(activity).apply { setContentView(dialogRoot); show() }
        }
        try {
            WindowRegressionCapture.frame(dialogRoot)
            // Ensure both windows have actually drawn their surface backgrounds before counting queues.
            val before = requireNotNullOnMain(material)
            WindowRegressionCapture.main {
                repeat(100) { material.notifyPositionChanged(target) }
            }
            val after = requireNotNullOnMain(material)
            assertEquals(100L, after.positionNotifications - before.positionNotifications)
            assertTrue(after.windowsQueued > before.windowsQueued)
            Log.i("LumenWork", "dialog before=$before after=$after")
        } finally {
            WindowRegressionCapture.main { dialog.dismiss() }
            WindowRegressionCapture.frame(root)
        }
    }

    @Test fun realtimeIdleWorkloadKeepsTheFallbackProbe() = withLiquid(true) { _, material, root ->
        val seconds = InstrumentationRegistry.getArguments().getString("lumen.workSeconds")?.toLongOrNull()?.coerceIn(3, 60) ?: 3L
        val deadline = SystemClock.uptimeMillis() + 8000
        var snapshot = requireNotNullOnMain(material)
        while (!snapshot.idle && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50); snapshot = requireNotNullOnMain(material)
        }
        assertTrue("A static realtime scene must reach idle", snapshot.idle)
        val before = snapshot
        SystemClock.sleep(seconds * 1000)
        val after = requireNotNullOnMain(material)
        assertTrue("The one-second fallback must remain active", after.idleProbeWakeups > before.idleProbeWakeups)
        assertTrue(after.identicalCaptures > before.identicalCaptures)
        Log.i("LumenWork", "idle seconds=$seconds before=$before after=$after")
        WindowRegressionCapture.main { root.invalidate() }
        WindowRegressionCapture.frame(root)
        val wakeDeadline = SystemClock.uptimeMillis() + 3000
        while (requireNotNullOnMain(material).captureRequests == after.captureRequests && SystemClock.uptimeMillis() < wakeDeadline) SystemClock.sleep(20)
        assertTrue("A real draw must wake sampling", requireNotNullOnMain(material).captureRequests > after.captureRequests)
    }

    private fun requireNotNullOnMain(material: LumenActivityDelegate): LiquidWorkDiagnostics {
        lateinit var result: LiquidWorkDiagnostics
        WindowRegressionCapture.main { result = requireNotNull(material.liquidWorkDiagnostics()) }
        return result
    }

    private fun withLiquid(realtime: Boolean, check: (SurfaceSandboxActivity, LumenActivityDelegate, FrameLayout) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldMaterial = LumenEngine.requestedMaterial(context)
        val oldRealtime = LumenEngine.isRealtimeCaptureEnabled(context)
        WindowRegressionCapture.main { assertTrue(LumenEngine.selectMaterial(context, SkinId.LIQUID, realtime)) }
        try {
            ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                lateinit var activity: SurfaceSandboxActivity
                lateinit var material: LumenActivityDelegate
                lateinit var root: FrameLayout
                scenario.onActivity { a ->
                    activity = a; a.binding.close(); a.session.close()
                    material = LumenActivityDelegate(a, { LumenPalette.neutral(false) })
                    assertNull(material.liquidWorkDiagnostics()); material.prepare()
                    root = FrameLayout(a).apply { keepScreenOn = true }
                    repeat(8) { index ->
                        root.addView(View(a).apply { background = material.cardBackground(Color.WHITE, 8f) },
                            FrameLayout.LayoutParams(180, 70).apply { topMargin = index * 80 })
                    }
                    a.setContentView(root); assertTrue(material.bindRoot(root)); material.onStart()
                }
                try { WindowRegressionCapture.frame(root); check(activity, material, root) }
                finally { WindowRegressionCapture.main { material.onDestroy(); assertNull(material.liquidWorkDiagnostics()) } }
            }
        } finally {
            WindowRegressionCapture.main { assertTrue(LumenEngine.selectMaterial(context, oldMaterial, oldRealtime)) }
        }
    }
}
