package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Color
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
import com.lumen.coacervation.engine.host.LumenAcrylicOptions
import com.lumen.coacervation.engine.host.LumenFluentControl
import com.lumen.coacervation.engine.host.LumenFluentControlOptions
import com.lumen.coacervation.engine.host.LumenFluentElevationOptions
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.model.LumenPalette
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The composite owns luminosity; ordinary FROSTED must retain the released illumination recipe. */
@RunWith(AndroidJUnit4::class)
class AcrylicBackdropColorTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun softwareAcrylicPreservesPaleColorsWhileOrdinaryFrostedStillIlluminates() = verify(software = true)

    @Test fun gpuAcrylicPreservesPaleColorsWhileOrdinaryFrostedStillIlluminates() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        verify(software = false)
    }

    private fun verify(software: Boolean) {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var source: View
            lateinit var acrylic: View
            lateinit var frosted: View
            lateinit var session: LumenSurfaceSession
            lateinit var control: LumenFluentControl
            lateinit var controlOptions: LumenFluentControlOptions
            lateinit var binding: LumenSurfaceBinding
            val backend = if (software) LumenSurfaceBackend.SOFTWARE else LumenSurfaceBackend.GPU
            scenario.onActivity { activity ->
                window = activity.window
                val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
                source = View(activity)
                acrylic = View(activity)
                frosted = View(activity)
                root.addView(source, FrameLayout.LayoutParams(600, 700))
                root.addView(acrylic, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 40; topMargin = 140 })
                root.addView(frosted, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 40; topMargin = 420 })
                activity.setContentView(root)
                // Public budget controls force real software sampling even on a GPU-capable device.
                session = LumenSurfaceSession(activity, LumenPalette.neutral(false),
                    LumenSurfaceSessionOptions(maxGpuSurfacePixels = if (software) 16_384 else 4_194_304))
                controlOptions = LumenFluentControlOptions(
                    acrylic = LumenAcrylicOptions(tintColor = Color.TRANSPARENT, tintOpacity = 0f,
                        luminosityOpacity = 0f, noiseOpacity = 0f, fallbackColor = Color.BLACK,
                        blurRadiusDp = 10f, fallbackOnLowRamDevice = false, fallbackOnPowerSave = false),
                    elevation = LumenFluentElevationOptions(shadowEnabled = false, strokeEnabled = false),
                    cornerRadiusDp = 0f, stateLayerEnabled = false, focusVisualEnabled = false, reduceMotion = true)
                control = LumenFluentControl(acrylic, LumenPalette.neutral(false), controlOptions, session, source)
                binding = session.bind(frosted, LumenSurfaceOptions(radiusDp = 0f, tintEnabled = false,
                    edgeEnabled = false, sampling = LumenSurfaceSampling(backend = backend,
                        blurRadiusDp = 10f, refractionEnabled = false)), source)
            }
            try {
                for ((index, color) in intArrayOf(0xfff4e1ee.toInt(), 0xffe0f4e7.toInt(), 0xfff4e1ee.toInt()).withIndex()) {
                    scenario.onActivity {
                        if (index == 1) {
                            control.updatePalette(LumenPalette.neutral(true))
                            controlOptions = controlOptions.copy(acrylic = controlOptions.acrylic.copy(blurRadiusDp = 12f))
                            control.update(controlOptions)
                        } else if (index == 2) {
                            control.update(controlOptions.copy(acrylic = controlOptions.acrylic.copy(enabled = false)))
                            control.update(controlOptions)
                        }
                        source.setBackgroundColor(color)
                        session.notifyContentChanged()
                    }
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    var ready = false
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity {
                            ready = control.diagnostics().backdropBackend == backend &&
                                control.diagnostics().acrylicVisible && binding.diagnostics()?.backend == backend
                        }
                        if (!ready) SystemClock.sleep(30)
                    }
                    assertTrue("Expected actual $backend sampling", ready)
                    // A previously ready backend may still be publishing the changed source's next frame.
                    SystemClock.sleep(300)
                    val image = capture(window)
                    try {
                        val actual = pixel(image, acrylic)
                        val ordinary = pixel(image, frosted)
                        for (shift in intArrayOf(0, 8, 16)) {
                            assertTrue("Acrylic backdrop changed $backend channel: ${Integer.toHexString(color)} -> ${Integer.toHexString(actual)}",
                                abs((color ushr shift and 255) - (actual ushr shift and 255)) <= 3)
                        }
                        assertTrue("Ordinary FROSTED illumination was removed on $backend: ${Integer.toHexString(ordinary)}",
                            Color.green(ordinary) >= Color.green(actual) + 6)
                    } finally { image.recycle() }
                }
            } finally { scenario.onActivity { control.close(); binding.close(); session.close() } }
        }
    }

    private fun pixel(image: Bitmap, view: View): Int {
        val position = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationInWindow(position) }
        return image.getPixel(position[0] + view.width / 2, position[1] + view.height / 2)
    }

    private fun capture(window: Window): Bitmap {
        var width = 0
        var height = 0
        instrumentation.runOnMainSync { width = window.decorView.width; height = window.decorView.height }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var status = -1
        PixelCopy.request(window, bitmap, { status = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, status)
        return bitmap
    }
}
