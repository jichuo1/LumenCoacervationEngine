package com.lumen.coacervation.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * PixelCopy checks the displayed output, rather than a successful API call. Every API level exercises
 * explicit-source fallback. Native-only branches run only on SDK 37.2+; a passing older-device run
 * provides no native visual, RenderThread invalidation, or performance acceptance evidence.
 */
@RunWith(AndroidJUnit4::class)
class RenderTargetBackdropTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun defaultAndOptInPreserveTheSimpleFrostedSourcePixels() = withStage { scenario, stage ->
        awaitSample(scenario, stage, native = false)
        val explicit = awaitColor(scenario, stage, Color.RED)
        scenario.onActivity {
            val state = stage.binding.renderTargetBackdropState()!!
            assertFalse(state.requested)
            assertFalse(state.active)
            assertEquals(0L, state.draws)
            stage.binding.setRenderTargetBackdropEnabled(true)
        }
        awaitSample(scenario, stage, native = nativeSupported())
        val selected = awaitColor(scenario, stage, Color.RED)
        assertNear("Opt-in changed the flat frosted backdrop", explicit, selected, 4)
        scenario.onActivity {
            val state = stage.binding.renderTargetBackdropState()!!
            assertTrue(state.requested)
            assertFalse(state.failed)
            assertEquals(nativeSupported(), state.active)
            if (nativeSupported()) assertTrue(state.draws > 0L) else assertEquals(0L, state.draws)
        }
    }

    @Test fun nativeUsesPriorDrawingOrderAndClipsBeforeReturningToExplicitSource() = withStage { scenario, stage ->
        enableAndAwait(scenario, stage)
        awaitColor(scenario, stage, Color.RED)
        if (!nativeSupported()) return@withStage

        val markerColor = Color.rgb(25, 80, 180)
        lateinit var marker: View
        scenario.onActivity { activity ->
            marker = View(activity).apply { setBackgroundColor(markerColor) }
            stage.root.addView(marker, 1, FrameLayout.LayoutParams(stage.target.width, stage.target.height).apply {
                leftMargin = stage.target.left; topMargin = stage.target.top
            })
        }
        // Source remains red. Only the prior destination contains this blue sibling.
        val processed = awaitColor(scenario, stage, markerColor)
        assertTrue("Prior sibling was not processed by the frosted shader", Color.red(processed) >= Color.red(markerColor) + 5)
        assertNear("Rounded corner changed pixels outside its output", markerColor,
            pixel(scenario, stage, localX = 2, localY = 2), 2)

        scenario.onActivity {
            stage.root.removeView(marker)
            stage.root.addView(marker) // The same opaque sibling now draws after the target.
        }
        awaitCondition("Later sibling was unexpectedly processed") {
            near(markerColor, pixel(scenario, stage), 2)
        }
        scenario.onActivity {
            stage.root.removeView(marker)
            stage.root.addView(marker, 1)
            stage.binding.setRenderTargetBackdropEnabled(false)
        }
        awaitSample(scenario, stage, native = false)
        awaitColor(scenario, stage, Color.RED)
        scenario.onActivity {
            assertFalse(stage.binding.renderTargetBackdropState()!!.active)
            assertFalse(stage.binding.renderTargetBackdropState()!!.requested)
        }
    }

    @Test fun nativeFollowsSourceOnlyChangesAndStopsExplicitRecording() = withStage { scenario, stage ->
        enableAndAwait(scenario, stage)
        awaitColor(scenario, stage, Color.RED)
        if (!nativeSupported()) return@withStage

        instrumentation.waitForIdleSync()
        var targetRecords = 0L
        var nativeDraws = 0L
        scenario.onActivity {
            targetRecords = stage.target.hardwareDraws
            nativeDraws = stage.binding.renderTargetBackdropState()!!.draws
            // Deliberately omit session/target invalidation: RenderThread must reread the destination.
            stage.source.colors(Color.BLUE)
        }
        val sourceUpdated = awaitColor(scenario, stage, Color.BLUE)
        assertTrue("Source-only update lost frosted processing", Color.red(sourceUpdated) >= 6 && Color.green(sourceUpdated) >= 6)
        scenario.onActivity {
            assertEquals("Source-only invalidation re-recorded the target", targetRecords, stage.target.hardwareDraws)
            assertEquals("Source-only invalidation rebuilt the native command", nativeDraws, stage.binding.renderTargetBackdropState()!!.draws)
        }

        var recordings = 0L
        scenario.onActivity { recordings = stage.session.diagnostics().contentRecordings }
        for (color in intArrayOf(Color.GREEN, Color.RED, Color.BLUE, Color.GREEN)) {
            scenario.onActivity {
                stage.source.colors(color)
                stage.target.invalidate()
            }
            awaitColor(scenario, stage, color)
            scenario.onActivity {
                assertTrue(stage.binding.renderTargetBackdropState()!!.active)
                assertEquals("Native frames kept recording the explicit source", recordings, stage.session.diagnostics().contentRecordings)
            }
        }
        scenario.onActivity { assertTrue(stage.binding.renderTargetBackdropState()!!.draws > nativeDraws) }
    }

    @Test fun nativeTracksLayoutSizeAndPositionWithoutDoubleApplyingTheTransform() = withStage { scenario, stage ->
        enableAndAwait(scenario, stage)
        awaitColor(scenario, stage, Color.RED)
        scenario.onActivity {
            stage.source.colors(Color.RED, Color.BLUE)
            stage.session.notifyContentChanged()
        }
        awaitColor(scenario, stage, Color.RED)
        scenario.onActivity {
            stage.target.layoutParams = (stage.target.layoutParams as FrameLayout.LayoutParams).apply {
                width = minOf(200, stage.root.width / 3)
                height = 100
                leftMargin = stage.root.width - width - 24
                topMargin = 260
            }
        }
        val moved = awaitColor(scenario, stage, Color.BLUE)
        assertTrue("Moved surface did not process its new destination", Color.red(moved) >= 6 && Color.green(moved) >= 6)
        scenario.onActivity {
            assertEquals(100, stage.target.height)
            assertEquals(nativeSupported(), stage.binding.renderTargetBackdropState()!!.active)
            stage.binding.setRenderTargetBackdropEnabled(false)
        }
        awaitSample(scenario, stage, native = false)
        awaitColor(scenario, stage, Color.BLUE)
    }

    @Test fun nativeBlurProcessesTheSameRealColorBoundaryAsExplicitFrosted() = withStage { scenario, stage ->
        awaitSample(scenario, stage, native = false)
        var centre = 0
        scenario.onActivity {
            stage.source.colors(Color.RED, Color.BLUE)
            stage.target.layoutParams = (stage.target.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = stage.root.width / 2 - width / 2
            }
            centre = (stage.target.layoutParams as FrameLayout.LayoutParams).width / 2
            stage.binding.update(surfaceOptions(blurRadiusDp = 6f))
            stage.session.notifyContentChanged()
        }
        awaitSample(scenario, stage, native = false)
        var explicitLeft = Color.TRANSPARENT
        var explicitRight = Color.TRANSPARENT
        awaitCondition("Explicit blur did not mix pixels across the source boundary") {
            explicitLeft = pixel(scenario, stage, localX = centre - 2)
            explicitRight = pixel(scenario, stage, localX = centre + 2)
            Color.blue(explicitLeft) > 25 && Color.red(explicitRight) > 25
        }
        scenario.onActivity { stage.binding.setRenderTargetBackdropEnabled(true) }
        awaitSample(scenario, stage, native = nativeSupported())
        assertNear("Native blur changed the red side of the same boundary", explicitLeft,
            pixel(scenario, stage, localX = centre - 2), 6)
        assertNear("Native blur changed the blue side of the same boundary", explicitRight,
            pixel(scenario, stage, localX = centre + 2), 6)
    }

    @Test fun actualSoftwareCanvasKeepsSampledPixelsAndNeverCallsTheNativeNode() = withStage(softwareLayer = true) { scenario, stage ->
        scenario.onActivity { stage.binding.setRenderTargetBackdropEnabled(true) }
        // The released first-visible diagnostic deliberately ignores a software recording inside a
        // hardware Window. PixelCopy below, and the direct bitmap assertion, verify actual visibility.
        awaitSample(scenario, stage, native = false, backend = LumenSurfaceBackend.SOFTWARE, requireVisibleFrame = false)
        awaitColor(scenario, stage, Color.RED)
        scenario.onActivity {
            assertTrue("The View did not receive a software Canvas", stage.target.softwareDraws > 0L)
            val bitmap = Bitmap.createBitmap(stage.target.width, stage.target.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                assertFalse(canvas.isHardwareAccelerated)
                stage.target.background.draw(canvas)
                assertDominant("Software fallback lost its sampled source", bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), Color.RED)
                val state = stage.binding.renderTargetBackdropState()!!
                assertTrue(state.requested)
                assertFalse(state.active)
                assertFalse(state.failed)
                assertEquals(0L, state.draws)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun pauseMemoryReleaseCloseAndDetachReleaseResourcesIdempotently() = withStage { scenario, stage ->
        enableAndAwait(scenario, stage)
        awaitColor(scenario, stage, Color.RED)
        scenario.onActivity {
            stage.session.pause(); stage.session.pause()
            assertReleased(stage)
            stage.session.resume()
        }
        awaitSample(scenario, stage, native = nativeSupported())
        scenario.onActivity {
            stage.session.releaseGraphics(); stage.session.releaseGraphics()
            assertReleased(stage)
            stage.session.resume()
        }
        awaitSample(scenario, stage, native = nativeSupported())
        scenario.onActivity {
            stage.binding.close(); stage.binding.close()
            assertNull(stage.binding.renderTargetBackdropState())
            assertEquals(0L, stage.session.performanceDiagnostics().logicalGpuEffectPixels)
            // Closing an active binding must leave the source and session usable for another one.
            stage.binding = stage.session.bind(stage.target, surfaceOptions(), stage.source)
            stage.binding.setRenderTargetBackdropEnabled(true)
        }
        awaitSample(scenario, stage, native = nativeSupported())
        scenario.onActivity {
            stage.root.removeView(stage.target)
            assertFalse(stage.binding.isBound)
            assertNull(stage.binding.renderTargetBackdropState())
            assertEquals(0, stage.session.diagnostics().attachedSurfaces)
            assertEquals(0L, stage.session.performanceDiagnostics().logicalGpuEffectPixels)
            stage.binding.close(); stage.binding.close()
            stage.session.close(); stage.session.close()
            stage.binding.setRenderTargetBackdropEnabled(true)
            assertNull(stage.binding.renderTargetBackdropState())
        }
    }

    private fun assertReleased(stage: Stage) {
        assertFalse(stage.binding.renderTargetBackdropState()!!.active)
        assertEquals(0L, stage.session.performanceDiagnostics().logicalGpuEffectPixels)
        assertEquals(0L, stage.session.performanceDiagnostics().logicalGpuContentPixels)
    }

    private class ColorSource(context: Context) : View(context) {
        private val paint = Paint()
        private var leftColor = Color.RED
        private var rightColor = Color.RED
        init { setWillNotDraw(false) }
        fun colors(left: Int, right: Int = left) { leftColor = left; rightColor = right; invalidate() }
        override fun onDraw(canvas: Canvas) {
            paint.color = leftColor
            canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
            paint.color = rightColor
            canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private class CountingTarget(context: Context) : View(context) {
        var hardwareDraws = 0L
        var softwareDraws = 0L
        init { setWillNotDraw(false) }
        override fun draw(canvas: Canvas) {
            if (canvas.isHardwareAccelerated) hardwareDraws++ else softwareDraws++
            super.draw(canvas)
        }
    }

    private class Stage(val window: Window, val root: FrameLayout, val source: ColorSource,
        val target: CountingTarget, val session: LumenSurfaceSession, var binding: LumenSurfaceBinding)

    private fun surfaceOptions(blurRadiusDp: Float = 0f): LumenSurfaceOptions =
        LumenSurfaceOptions(material = LumenSurfaceMaterial.FROSTED, radiusDp = 12f, opacity = 1f,
            tintEnabled = false, edgeEnabled = false, sampling = LumenSurfaceSampling(
                backend = LumenSurfaceBackend.AUTO, blurEnabled = blurRadiusDp > 0f,
                blurRadiusDp = blurRadiusDp, refractionEnabled = false, fadeEnabled = false, minIntervalMs = 0L))

    private fun withStage(softwareLayer: Boolean = false,
        block: (ActivityScenario<FluentSandboxActivity>, Stage) -> Unit) {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            lateinit var stage: Stage
            scenario.onActivity { activity ->
                val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
                val source = ColorSource(activity)
                val target = CountingTarget(activity).apply {
                    if (softwareLayer) setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
                root.addView(source, FrameLayout.LayoutParams(-1, -1))
                root.addView(target, FrameLayout.LayoutParams(minOf(160, activity.resources.displayMetrics.widthPixels / 3), 120).apply {
                    leftMargin = 24; topMargin = 160
                })
                activity.setContentView(root)
                val session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                val binding = session.bind(target, surfaceOptions(), source)
                stage = Stage(activity.window, root, source, target, session, binding)
            }
            try { block(scenario, stage) }
            finally { scenario.onActivity { stage.binding.close(); stage.session.close() } }
        }
    }

    private fun enableAndAwait(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage) {
        scenario.onActivity { stage.binding.setRenderTargetBackdropEnabled(true) }
        awaitSample(scenario, stage, native = nativeSupported())
    }

    private fun awaitSample(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, native: Boolean,
        backend: LumenSurfaceBackend = if (Build.VERSION.SDK_INT >= 31) LumenSurfaceBackend.GPU else LumenSurfaceBackend.SOFTWARE,
        requireVisibleFrame: Boolean = true) {
        awaitCondition("Expected $backend with native=$native") {
            var ready = false
            scenario.onActivity {
                val state = stage.binding.renderTargetBackdropState()
                val surface = stage.binding.diagnostics()
                ready = state != null && state.active == native && !state.failed &&
                    surface?.backend == backend && (!requireVisibleFrame || surface.hasVisibleFrame)
            }
            ready
        }
    }

    private fun nativeSupported(): Boolean = Build.VERSION.SDK_INT >= 36 &&
        Build.VERSION.SDK_INT >= 37 && Build.VERSION.SDK_INT_FULL >= 3_700_002

    private fun awaitColor(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, expected: Int): Int {
        var seen = Color.TRANSPARENT
        awaitCondition("Displayed backdrop did not follow ${Integer.toHexString(expected)}") {
            seen = pixel(scenario, stage)
            val primary = expected == Color.RED || expected == Color.GREEN || expected == Color.BLUE
            // The naked source has the same dominant channel while a fallback frame is pending.
            // Its zero channels must gain the ordinary frost's small illumination before acceptance.
            dominant(seen) == dominant(expected) && Color.alpha(seen) >= 250 &&
                (!primary || minOf(Color.red(seen), Color.green(seen), Color.blue(seen)) >= 5)
        }
        assertDominant("Unexpected backdrop pixel", seen, expected)
        return seen
    }

    private fun dominant(color: Int): Int {
        val channels = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
        return channels.indices.maxByOrNull { channels[it] }!!
    }

    private fun assertDominant(message: String, actual: Int, expected: Int) {
        assertEquals("$message: ${Integer.toHexString(actual)}", dominant(expected), dominant(actual))
        assertEquals(255, Color.alpha(actual))
    }

    private fun near(expected: Int, actual: Int, tolerance: Int): Boolean =
        intArrayOf(0, 8, 16, 24).all { abs((expected ushr it and 255) - (actual ushr it and 255)) <= tolerance }

    private fun assertNear(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: ${Integer.toHexString(expected)} -> ${Integer.toHexString(actual)}", near(expected, actual, tolerance))
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            if (condition()) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        fail(message)
    }

    private fun pixel(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage,
        localX: Int = -1, localY: Int = -1): Int {
        var x = 0
        var y = 0
        lateinit var image: Bitmap
        scenario.onActivity {
            val location = IntArray(2)
            stage.target.getLocationInWindow(location)
            x = location[0] + if (localX < 0) stage.target.width / 2 else localX
            y = location[1] + if (localY < 0) stage.target.height / 2 else localY
            image = Bitmap.createBitmap(stage.window.decorView.width, stage.window.decorView.height, Bitmap.Config.ARGB_8888)
        }
        try {
            var result = PixelCopy.ERROR_UNKNOWN
            awaitCondition("PixelCopy could not read the displayed window") {
                val done = CountDownLatch(1)
                scenario.onActivity {
                    PixelCopy.request(stage.window, image, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue("PixelCopy callback did not arrive", done.await(5, TimeUnit.SECONDS))
                result == PixelCopy.SUCCESS
            }
            return image.getPixel(x, y)
        } finally { image.recycle() }
    }
}
