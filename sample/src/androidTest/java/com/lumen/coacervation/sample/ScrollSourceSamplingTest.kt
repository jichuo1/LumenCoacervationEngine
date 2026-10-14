package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A ScrollView used directly as the content source: the sampled surface must follow its scroll offset.
 * Regression: sources were recorded with a bare View.draw(), which omits the view's own scroll, so the
 * surface kept showing the content at scroll position 0 (GPU and software paths alike).
 * Second regression (API 31 only): Android 12 HWUI kept compositing the glass's first filtered image after the
 * content changed, so the GPU path stayed at scroll position 0 there (FilteredLayerRefresh).
 */
@RunWith(AndroidJUnit4::class)
class ScrollSourceSamplingTest {
    // ATD images disable final hardware output while still running View draw callbacks; PixelCopy needs it.
    @get:Rule val hardwareOutput = HardwareOutputRule()

    private class Stage(val band: Int, val root: FrameLayout, val scroll: ScrollView, val target: View,
        val session: LumenSurfaceSession, val binding: LumenSurfaceBinding)

    private fun install(activity: FluentSandboxActivity, band: Int, backend: LumenSurfaceBackend): Stage {
        val bands = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        for (color in intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.RED))
            bands.addView(View(activity).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(-1, band))
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(bands) }
        val target = View(activity)
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.WHITE)
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            // The surface sits over the middle of the first band; it is a sibling, never inside the source.
            addView(target, FrameLayout.LayoutParams(band / 2, band / 2).apply { leftMargin = band / 4; topMargin = band / 4 })
        }
        (activity.findViewById<ViewGroup>(android.R.id.content)).addView(root, FrameLayout.LayoutParams(-1, -1))
        val session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
        val binding = session.bind(target, LumenSurfaceOptions(radiusDp = 0f, tintEnabled = false, edgeEnabled = false,
            sampling = LumenSurfaceSampling(backend = backend, blurEnabled = false, refractionEnabled = false, minIntervalMs = 0L)), scroll)
        return Stage(band, root, scroll, target, session, binding)
    }

    /** Index of the strongest channel at the surface centre: 0 red, 1 green, 2 blue. */
    private fun dominantUnderSurface(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, redrawTarget: Boolean): Int {
        var image: Bitmap? = null
        var x = 0
        var y = 0
        scenario.onActivity { activity ->
            val location = IntArray(2)
            stage.target.getLocationInWindow(location)
            x = location[0] + stage.target.width / 2
            y = location[1] + stage.target.height / 2
            val decor = activity.window.decorView
            image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        }
        val bitmap = image!!
        var result = PixelCopy.ERROR_UNKNOWN
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            val latch = CountDownLatch(1)
            scenario.onActivity { activity ->
                if (redrawTarget) stage.target.invalidate()
                PixelCopy.request(activity.window, bitmap, { code -> result = code; latch.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("PixelCopy callback did not arrive", latch.await(5, TimeUnit.SECONDS))
            if (result != PixelCopy.SUCCESS) SystemClock.sleep(50)
        } while (result != PixelCopy.SUCCESS && SystemClock.uptimeMillis() < deadline)
        assertEquals(PixelCopy.SUCCESS, result)
        val pixel = bitmap.getPixel(x, y)
        bitmap.recycle()
        val channels = intArrayOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        return channels.indices.maxByOrNull { channels[it] }!!
    }

    private fun waitForDominant(scenario: ActivityScenario<FluentSandboxActivity>, stage: Stage, expected: Int,
        redrawTarget: Boolean): Int {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var seen: Int
        do {
            seen = dominantUnderSurface(scenario, stage, redrawTarget)
            if (seen == expected) return seen
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return seen
    }

    /** AUTO uses the GPU node path on API 31+ and the software sampler below it. */
    @Test fun scrollViewSourceFollowsItsScrollOffset() = followsScroll(LumenSurfaceBackend.AUTO)

    /** Forces the software sampler on every API level, so both recorders are covered on any device. */
    @Test fun softwareSamplerFollowsTheScrollOffset() = followsScroll(LumenSurfaceBackend.SOFTWARE)

    /**
     * Nothing but the scroll invalidates: the engine's own content-only path must reach the GPU glass.
     * On API 31 that path has to make the target re-record its glass (FilteredLayerRefresh).
     */
    @SdkSuppress(minSdkVersion = 31)
    @Test fun gpuGlassFollowsContentOnlyRecords() = followsScroll(LumenSurfaceBackend.GPU, redrawTarget = false)

    private fun followsScroll(backend: LumenSurfaceBackend, redrawTarget: Boolean = true) {
        ActivityScenario.launch(FluentSandboxActivity::class.java).use { scenario ->
            var stage: Stage? = null
            scenario.onActivity { activity ->
                // Four half-screen bands: two screens of content, so the view can scroll two bands.
                val band = activity.resources.displayMetrics.heightPixels / 2
                stage = install(activity, band, backend)
            }
            val s = stage!!
            try {
                val deadline = SystemClock.uptimeMillis() + 5_000
                var sampled = false
                while (!sampled && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { sampled = s.binding.diagnostics()?.let { it.hasVisibleFrame && it.backend != LumenSurfaceBackend.STATIC } == true }
                    if (!sampled) SystemClock.sleep(30)
                }
                assertTrue("surface never sampled its source", sampled)
                if (backend == LumenSurfaceBackend.GPU) scenario.onActivity {
                    assertEquals("forced GPU binding must draw through the glass node", LumenSurfaceBackend.GPU, s.binding.diagnostics()?.backend)
                }
                assertEquals("surface over the red band at scroll 0", 0, waitForDominant(scenario, s, 0, redrawTarget))
                scenario.onActivity { assertTrue("content must be scrollable by two bands", s.scroll.getChildAt(0).height - s.scroll.height >= 2 * s.band) }
                // Scroll one band: green is now under the surface, in the source and in the samples alike.
                scenario.onActivity { s.scroll.scrollTo(0, s.band); s.session.notifyScrollPositionChanged(s.scroll) }
                assertEquals("surface must follow the source's own scroll", 1, waitForDominant(scenario, s, 1, redrawTarget))
                scenario.onActivity { s.scroll.scrollTo(0, 2 * s.band); s.session.notifyScrollPositionChanged(s.scroll) }
                assertEquals("surface must follow the source's own scroll", 2, waitForDominant(scenario, s, 2, redrawTarget))
            } finally {
                scenario.onActivity {
                    s.binding.close(); s.session.close()
                    (s.root.parent as ViewGroup).removeView(s.root)
                }
            }
        }
    }
}
