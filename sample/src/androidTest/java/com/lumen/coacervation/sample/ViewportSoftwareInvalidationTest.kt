package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenViewportContentSource
import com.lumen.coacervation.engine.model.LumenPalette
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewportSoftwareInvalidationTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun softwareViewportRefreshesDescendantInvalidationAndPropertyMovement() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var root: FrameLayout
            lateinit var owner: FrameLayout
            lateinit var detail: View
            lateinit var glass: View
            lateinit var source: LumenViewportContentSource
            lateinit var session: LumenSurfaceSession
            lateinit var binding: LumenSurfaceBinding
            lateinit var sync: ViewTreeObserver.OnPreDrawListener
            var color = Color.RED
            scenario.onActivity { activity ->
                activity.session.close()
                window = activity.window
                root = FrameLayout(activity)
                owner = FrameLayout(activity).apply { setBackgroundColor(Color.BLUE) }
                detail = object : View(activity) {
                    override fun onDraw(canvas: Canvas) = canvas.drawColor(color)
                }
                owner.addView(detail, FrameLayout.LayoutParams(-1, 200).apply { topMargin = 300 })
                glass = View(activity)
                root.addView(owner, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 160 })
                root.addView(glass, FrameLayout.LayoutParams(240, 80).apply { leftMargin = 40; topMargin = 480 })
                activity.setContentView(root)
                source = LumenViewportContentSource(owner) { use -> use(glass, 0f, 0f, 8f) }
                // Geometry sync precedes the session, without an explicit content/position notice.
                sync = ViewTreeObserver.OnPreDrawListener { source.sync(); true }
                root.viewTreeObserver.addOnPreDrawListener(sync)
                session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                binding = session.bindSource(glass, LumenSurfaceOptions(radiusDp = 0f,
                    tintEnabled = false, edgeEnabled = false,
                    sampling = LumenSurfaceSampling(backend = LumenSurfaceBackend.SOFTWARE,
                        blurEnabled = false, refractionEnabled = false, minIntervalMs = 0L)), source)
            }
            fun awaitColor(expected: Int) {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                var last = Color.TRANSPARENT
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    scenario.onActivity {
                        ready = glass.width > 0 && binding.diagnostics()?.backend == LumenSurfaceBackend.SOFTWARE
                    }
                    if (ready) {
                        last = pixel(scenario, window, glass)
                        val expectedChannel = when (expected) {
                            Color.RED -> Color.red(last)
                            Color.GREEN -> Color.green(last)
                            else -> Color.blue(last)
                        }
                        val otherChannels = Color.red(last) + Color.green(last) + Color.blue(last) - expectedChannel
                        if (expectedChannel > 200 && otherChannels < 60) return
                    }
                    SystemClock.sleep(30)
                }
                fail("Software viewport did not update to ${Integer.toHexString(expected)}: ${Integer.toHexString(last)}")
            }
            fun awaitQuiet() {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                var last = -1L
                var stableSince = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() < deadline) {
                    var count = 0L
                    scenario.onActivity { count = session.diagnostics().contentRecordings }
                    if (count != last) { last = count; stableSince = SystemClock.uptimeMillis() }
                    if (SystemClock.uptimeMillis() - stableSince >= 200L) return
                    SystemClock.sleep(25)
                }
                fail("A static cropped source kept recording")
            }
            try {
                awaitColor(Color.RED)
                awaitQuiet()
                scenario.onActivity {
                    assertFalse("The fixture owner must have settled", owner.isDirty)
                    color = Color.GREEN
                    detail.invalidate()
                    assertTrue(detail.isDirty)
                    // Parent dirty propagation varies by Android version; visible pixels must update either way.
                }
                awaitColor(Color.GREEN)
                awaitQuiet()
                scenario.onActivity { detail.translationY = 240f }
                awaitColor(Color.BLUE)
                awaitQuiet()
                scenario.onActivity {
                    assertEquals(LumenSurfaceBackend.SOFTWARE, binding.diagnostics()?.backend)
                }
            } finally {
                scenario.onActivity {
                    root.viewTreeObserver.removeOnPreDrawListener(sync)
                    session.close(); source.close()
                }
            }
        }
    }

    private fun pixel(scenario: ActivityScenario<SurfaceSandboxActivity>, window: Window, target: View): Int {
        val location = IntArray(2)
        lateinit var image: Bitmap
        scenario.onActivity {
            target.getLocationInWindow(location)
            image = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        }
        return try {
            var result = PixelCopy.ERROR_UNKNOWN
            val done = CountDownLatch(1)
            PixelCopy.request(window, image, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("PixelCopy callback did not arrive", done.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            image.getPixel(location[0] + target.width / 2, location[1] + target.height / 2)
        } finally { image.recycle() }
    }
}
