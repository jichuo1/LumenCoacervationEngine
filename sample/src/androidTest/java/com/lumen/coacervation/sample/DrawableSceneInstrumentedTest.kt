package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
import com.lumen.coacervation.engine.host.LumenDrawableScene
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawableSceneInstrumentedTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun softwareRecordingKeepsNativePixelsAndBorrowedDrawableStateAndCloseIsFinal() {
        instrumentation.runOnMainSync {
            val scene = LumenDrawableScene.create(hardware = false, scale = .25f)
            val drawable = object : Drawable() {
                private val paint = Paint()
                var draws = 0
                override fun draw(canvas: Canvas) {
                    draws++
                    paint.color = Color.RED
                    canvas.drawRect(bounds, paint)
                    paint.color = Color.BLUE
                    canvas.drawRect(50f, 0f, 51f, 63f, paint)
                }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: ColorFilter?) = Unit
                @Suppress("OVERRIDE_DEPRECATION")
                override fun getOpacity() = PixelFormat.OPAQUE
            }.apply { setBounds(0, 0, 101, 63) }
            val callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = Unit
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            }
            drawable.callback = callback
            try {
                scene.record(drawable, 101, 63)
                assertSame(callback, drawable.callback)
                assertEquals(android.graphics.Rect(0, 0, 101, 63), drawable.bounds)
                val pixels = renderSoftware(scene, 101, 63)
                try {
                    assertEquals(Color.RED, pixels.getPixel(49, 31))
                    assertEquals(Color.BLUE, pixels.getPixel(50, 31))
                    assertEquals(Color.RED, pixels.getPixel(51, 31))
                } finally { pixels.recycle() }
                scene.close()
                scene.close()
                scene.record(drawable, 101, 63)
                assertEquals("Closed scenes must not call the borrowed Drawable", 1, drawable.draws)
                val closed = renderSoftware(scene, 101, 63)
                try { assertEquals(Color.TRANSPARENT, closed.getPixel(50, 31)) }
                finally { closed.recycle() }
            } finally { scene.close() }
        }
    }

    @Test fun pre29HardwareRequestsUseAFullResolutionSoftwareRecording() {
        assumeTrue(Build.VERSION.SDK_INT < 29)
        instrumentation.runOnMainSync {
            val scene = LumenDrawableScene.create(hardware = true, scale = .5f)
            try {
                scene.record(ColorDrawable(Color.BLUE).apply { setBounds(0, 0, 101, 63) }, 101, 63)
                val pixels = renderSoftware(scene, 101, 63)
                try { assertEquals(Color.BLUE, pixels.getPixel(100, 62)) }
                finally { pixels.recycle() }
            } finally { scene.close() }
        }
    }

    @Test fun failedSoftwareRecordingIsNotReplayedAndTheNextRecordCanRecover() {
        instrumentation.runOnMainSync {
            val scene = LumenDrawableScene.create(hardware = false)
            try {
                val failing = object : ColorDrawable(Color.RED) {
                    override fun draw(canvas: Canvas) { super.draw(canvas); error("Recording interrupted") }
                }.apply { setBounds(0, 0, 16, 16) }
                try { scene.record(failing, 16, 16); fail("The recording failure must reach the caller") }
                catch (_: IllegalStateException) { /* expected */ }
                val partial = renderSoftware(scene, 16, 16)
                try { assertEquals(Color.TRANSPARENT, partial.getPixel(8, 8)) }
                finally { partial.recycle() }
                scene.record(ColorDrawable(Color.BLUE).apply { setBounds(0, 0, 16, 16) }, 16, 16)
                val recovered = renderSoftware(scene, 16, 16)
                try { assertEquals(Color.BLUE, recovered.getPixel(8, 8)) }
                finally { recovered.recycle() }
            } finally { scene.close() }
        }
    }

    @Test fun hardwareResizeAndFailureKeepCachedReferencesSafeAndSoftwareCaptureIndependent() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var target: View
            lateinit var window: Window
            lateinit var hardware: LumenDrawableScene
            lateinit var software: LumenDrawableScene
            var draws = 0
            scenario.onActivity { activity ->
                window = activity.window
                hardware = LumenDrawableScene.create(hardware = true, scale = .5f)
                software = LumenDrawableScene.create(hardware = false, scale = .5f)
                hardware.record(ColorDrawable(Color.RED).apply { setBounds(0, 0, 161, 101) }, 161, 101)
                root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
                target = object : View(activity) {
                    override fun onDraw(canvas: Canvas) { draws++; hardware.draw(canvas) }
                }
                root.addView(target, FrameLayout.LayoutParams(241, 161).apply { leftMargin = 24; topMargin = 120 })
                activity.setContentView(root)
            }
            try {
                settle()
                assertWindowPixel(window, target, 80, 50, Color.RED)
                assertWindowPixel(window, target, 220, 140, Color.BLACK)
                var cachedDraws = 0
                instrumentation.runOnMainSync {
                    cachedDraws = draws
                    val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                    try {
                        try { hardware.draw(Canvas(bitmap)); fail("Hardware scenes must reject software Canvas") }
                        catch (_: IllegalArgumentException) { /* expected */ }
                    } finally { bitmap.recycle() }
                    software.record(ColorDrawable(Color.BLUE).apply { setBounds(0, 0, 241, 161) }, 241, 161)
                    val sampled = renderSoftware(software, 241, 161)
                    try { assertEquals(Color.BLUE, sampled.getPixel(120, 80)) }
                    finally { sampled.recycle() }
                    val failing = object : ColorDrawable(Color.YELLOW) {
                        override fun draw(canvas: Canvas) { super.draw(canvas); error("Recording interrupted") }
                    }.apply { setBounds(0, 0, 241, 161) }
                    try { hardware.record(failing, 241, 161); fail("The recording failure must reach the caller") }
                    catch (_: IllegalStateException) { /* expected */ }
                    root.invalidate()
                }
                settle()
                assertWindowPixel(window, target, 80, 50, Color.BLACK)
                instrumentation.runOnMainSync {
                    assertEquals("Failure must clear the node already held by the cached child", cachedDraws, draws)
                    hardware.record(ColorDrawable(Color.GREEN).apply { setBounds(0, 0, 241, 161) }, 241, 161)
                    // Invalidate the parent, retaining the child's existing display list and node reference.
                    root.invalidate()
                }
                settle()
                assertWindowPixel(window, target, 220, 140, Color.GREEN)
                instrumentation.runOnMainSync {
                    assertEquals("Resize must update the node referenced by the cached child", cachedDraws, draws)
                    val sampled = renderSoftware(software, 241, 161)
                    try { assertEquals(Color.BLUE, sampled.getPixel(120, 80)) }
                    finally { sampled.recycle() }
                    software.close()
                    root.invalidate()
                }
                settle()
                assertWindowPixel(window, target, 220, 140, Color.GREEN)
                instrumentation.runOnMainSync { hardware.close(); root.invalidate() }
                settle()
                assertWindowPixel(window, target, 80, 50, Color.BLACK)
            } finally {
                instrumentation.runOnMainSync { hardware.close(); software.close() }
            }
        }
    }

    private fun renderSoftware(scene: LumenDrawableScene, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scene.draw(Canvas(it)) }

    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(120) }

    private fun assertWindowPixel(window: Window, target: View, x: Int, y: Int, expected: Int) {
        val location = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            target.getLocationInWindow(location)
            width = window.decorView.width
            height = window.decorView.height
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val done = CountDownLatch(1)
            var status = -1
            PixelCopy.request(window, bitmap, { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, status)
            assertEquals(expected, bitmap.getPixel(location[0] + x, location[1] + y))
        } finally { bitmap.recycle() }
    }
}
