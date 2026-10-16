package com.lumen.coacervation.sample

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
import com.lumen.coacervation.engine.host.LumenContentSource
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Equal declared frames must sample equally even when custom sources paint different exterior pixels. */
@RunWith(AndroidJUnit4::class)
class SurfaceSourceFrameClipTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sampleX = intArrayOf(6, 12, 24)
    private val sampleY = intArrayOf(30, 50, 70)

    @Test fun softwareHaloIgnoresPixelsOutsideDeclaredSourceFrame() = verify(LumenSurfaceBackend.SOFTWARE)

    @Test fun gpuHaloIgnoresPixelsOutsideDeclaredSourceFrame() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        verify(LumenSurfaceBackend.GPU)
    }

    private class Source(override val coordinateView: View, private val exterior: Int) : LumenContentSource {
        override val excludesSurfaces = true
        private val paint = Paint()
        override fun drawContent(canvas: Canvas) {
            val width = coordinateView.width.toFloat()
            val height = coordinateView.height.toFloat()
            paint.color = exterior
            canvas.drawRect(-512f, -512f, width + 512f, height + 512f, paint)
            paint.color = 0xff00a000.toInt()
            canvas.drawRect(0f, 0f, width, height, paint)
        }
    }

    private fun verify(backend: LumenSurfaceBackend) {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var session: LumenSurfaceSession
            lateinit var first: View
            lateinit var second: View
            lateinit var firstBinding: LumenSurfaceBinding
            lateinit var secondBinding: LumenSurfaceBinding
            scenario.onActivity { activity ->
                window = activity.window
                val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
                val left = View(activity)
                val right = View(activity)
                first = View(activity)
                second = View(activity)
                root.addView(left, FrameLayout.LayoutParams(200, 160).apply { leftMargin = 40; topMargin = 40 })
                root.addView(right, FrameLayout.LayoutParams(200, 160).apply { leftMargin = 40; topMargin = 220 })
                root.addView(first, FrameLayout.LayoutParams(80, 100).apply { leftMargin = 44; topMargin = 70 })
                root.addView(second, FrameLayout.LayoutParams(80, 100).apply { leftMargin = 44; topMargin = 250 })
                activity.setContentView(root)
                session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                val options = LumenSurfaceOptions(radiusDp = 0f, tintEnabled = false, edgeEnabled = false,
                    sampling = LumenSurfaceSampling(backend = backend, blurRadiusDp = 24f, refractionEnabled = false))
                firstBinding = session.bindSource(first, options, Source(left, Color.RED))
                secondBinding = session.bindSource(second, options, Source(right, Color.BLUE))
            }
            try {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var ready = false
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity {
                        if (first.width > 0 && second.width > 0) {
                            assertSamplingPointsInsideWindow(window, first)
                            assertSamplingPointsInsideWindow(window, second)
                        }
                        ready = firstBinding.diagnostics()?.backend == backend && secondBinding.diagnostics()?.backend == backend
                    }
                    if (!ready) SystemClock.sleep(30)
                }
                assertTrue("Expected two actual $backend samples", ready)
                SystemClock.sleep(150)
                val frame = capture(window)
                try {
                    val a = IntArray(2)
                    val b = IntArray(2)
                    instrumentation.runOnMainSync { first.getLocationInWindow(a); second.getLocationInWindow(b) }
                    for (x in sampleX) for (y in sampleY) {
                        val redExterior = frame.getPixel(a[0] + x, a[1] + y)
                        val blueExterior = frame.getPixel(b[0] + x, b[1] + y)
                        for (shift in intArrayOf(0, 8, 16)) {
                            assertTrue("$backend halo read undeclared exterior at $x,$y: ${Integer.toHexString(redExterior)} / ${Integer.toHexString(blueExterior)}",
                                abs((redExterior ushr shift and 255) - (blueExterior ushr shift and 255)) <= 3)
                        }
                        assertTrue("$backend lost the declared green frame", Color.green(redExterior) > Color.red(redExterior) + 20 &&
                            Color.green(redExterior) > Color.blue(redExterior) + 20)
                    }
                } finally { frame.recycle() }
            } finally { scenario.onActivity { firstBinding.close(); secondBinding.close(); session.close() } }
        }
    }

    private fun assertSamplingPointsInsideWindow(window: Window, target: View) {
        val position = IntArray(2)
        target.getLocationInWindow(position)
        val width = window.decorView.width
        val height = window.decorView.height
        assertTrue("The fixture window must be laid out", width > 0 && height > 0)
        for (x in sampleX) for (y in sampleY) {
            assertTrue("The sample point must lie inside the target", x in 0 until target.width && y in 0 until target.height)
            assertTrue("Fixture sample ${position[0] + x},${position[1] + y} is outside the ${width}x$height window",
                position[0] + x in 0 until width && position[1] + y in 0 until height)
        }
    }

    private fun capture(window: Window): Bitmap {
        var width = 0
        var height = 0
        instrumentation.runOnMainSync { width = window.decorView.width; height = window.decorView.height }
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = -1
        PixelCopy.request(window, frame, { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, status)
        return frame
    }
}
