package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.host.LumenPrismFallbackReason
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Prism glass and the prism selection box on a real window: fallback colours and tint compositing in both
 * shades, background restoration, and the classic selection box staying pixel-identical after a round trip.
 */
@RunWith(AndroidJUnit4::class)
class PrismIntegrationTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()

    /** Everything but the tint off, so a pixel is exactly backdrop ⊕ tint. */
    private val bare = LumenPrismOptions(cornerRadiusDp = 28f).let {
        it.copy(blur = it.blur.copy(enabled = false, saturation = 1f), lens = it.lens.copy(enabled = false),
            highlight = it.highlight.copy(enabled = false), press = it.press.copy(enabled = false),
            shadow = it.shadow.copy(enabled = false))
    }

    private fun capture(scenario: ActivityScenario<PrismSandboxActivity>): Bitmap {
        var image: Bitmap? = null
        scenario.onActivity { image = Bitmap.createBitmap(it.window.decorView.width, it.window.decorView.height, Bitmap.Config.ARGB_8888) }
        val bitmap = image!!
        var result = PixelCopy.ERROR_UNKNOWN
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            val latch = CountDownLatch(1)
            scenario.onActivity { activity ->
                PixelCopy.request(activity.window, bitmap, { code -> result = code; latch.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue("PixelCopy callback did not arrive", latch.await(5, TimeUnit.SECONDS))
            if (result != PixelCopy.SUCCESS) SystemClock.sleep(50)
        } while (result != PixelCopy.SUCCESS && SystemClock.uptimeMillis() < deadline)
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun boundsInWindow(view: View): Rect {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /** A point inside the card, clear of its label, rim and corners. */
    private fun cardProbe(scenario: ActivityScenario<PrismSandboxActivity>): IntArray {
        var point = IntArray(2)
        scenario.onActivity { activity ->
            val b = boundsInWindow(activity.cardView)
            val inset = (16 * activity.resources.displayMetrics.density).toInt()
            point = intArrayOf(b.left + inset, b.top + inset)
        }
        return point
    }

    private fun settle(scenario: ActivityScenario<PrismSandboxActivity>, millis: Long = 400) {
        SystemClock.sleep(millis)
        scenario.onActivity { }
    }

    @Test fun fallbackFollowsTheShadeAndCloseRestoresTheBackground() {
        ActivityScenario.launch(PrismSandboxActivity::class.java).use { scenario ->
            for ((dark, expected) in listOf(false to 0xFFFAFAFA.toInt(), true to 0xFF121212.toInt())) {
                scenario.onActivity { activity ->
                    activity.card.update(bare.copy(reduceTransparency = true))
                    activity.card.updatePalette(com.lumen.coacervation.engine.model.LumenPalette.neutral(dark))
                }
                settle(scenario, 500)
                val p = cardProbe(scenario)
                val pixel = capture(scenario).getPixel(p[0], p[1])
                assertClose("fallback dark=$dark", expected, pixel, 2)
                scenario.onActivity { activity ->
                    val d = activity.card.diagnostics()
                    assertEquals(LumenPrismFallbackReason.REDUCE_TRANSPARENCY, d.fallbackReason)
                    assertEquals(dark, d.dark)
                }
            }
            scenario.onActivity { activity ->
                val original = activity.cardView.background
                activity.card.close()
                assertTrue(activity.card.diagnostics().closed)
                assertFalse(activity.cardView.background === original)
                // The card had no background before the glass: close restores exactly that.
                assertEquals(null, activity.cardView.background)
                activity.card.update(bare) // no-op after close
            }
        }
    }

    /** GPU path: with only the tint on, the glass pixel is the backdrop pixel with the shade's tint composited at 40%. */
    @SdkSuppress(minSdkVersion = 31)
    @Test fun tintCompositesOverTheSampledBackdropInBothShades() {
        ActivityScenario.launch(PrismSandboxActivity::class.java).use { scenario ->
            for ((dark, tint) in listOf(false to 0xFAFAFA, true to 0x121212)) {
                scenario.onActivity { activity ->
                    activity.card.updatePalette(com.lumen.coacervation.engine.model.LumenPalette.neutral(dark))
                    activity.card.update(bare.copy(surface = bare.surface.copy(tintOpacity = 0f)))
                }
                waitForBackend(scenario)
                val p = cardProbe(scenario)
                val backdrop = capture(scenario).getPixel(p[0], p[1])
                scenario.onActivity { it.card.update(bare.copy(surface = bare.surface.copy(tintOpacity = 0.4f))) }
                waitForBackend(scenario)
                val tinted = capture(scenario).getPixel(p[0], p[1])
                val expected = Color.rgb(
                    mix(Color.red(backdrop), (tint shr 16) and 0xFF), mix(Color.green(backdrop), (tint shr 8) and 0xFF),
                    mix(Color.blue(backdrop), tint and 0xFF))
                assertClose("tint dark=$dark backdrop=${Integer.toHexString(backdrop)}", expected, tinted, 4)
            }
        }
    }

    @Test fun classicSelectionIsPixelIdenticalAfterAPrismRoundTrip() {
        ActivityScenario.launch(PrismSandboxActivity::class.java).use { scenario ->
            var region = Rect()
            scenario.onActivity { activity ->
                activity.bar.close() // compare the selection box alone, not the live glass under it
                activity.navigation.setPrismSelection(null)
                region = boundsInWindow(activity.navigation)
            }
            settle(scenario, 600)
            val before = capture(scenario)
            scenario.onActivity { it.navigation.setPrismSelection(com.lumen.coacervation.engine.widget.LumenPrismSelectionOptions()) }
            settle(scenario, 300)
            scenario.onActivity { it.navigation.setPrismSelection(null); assertFalse(it.navigation.isPrismSelectionEnabled) }
            settle(scenario, 600)
            val after = capture(scenario)
            var differing = 0
            for (y in region.top until region.bottom) for (x in region.left until region.right) {
                if (before.getPixel(x, y) != after.getPixel(x, y)) differing++
            }
            assertEquals("classic selection pixels changed after a prism round trip", 0, differing)
        }
    }

    @Test fun prismSelectionShowsTheSelectedColourOnlyInsideThePill() {
        ActivityScenario.launch(PrismSandboxActivity::class.java).use { scenario ->
            val icons = ArrayList<Rect>()
            scenario.onActivity { activity ->
                activity.bar.close()
                activity.navigation.setPrismSelection(com.lumen.coacervation.engine.widget.LumenPrismSelectionOptions())
                val bar = activity.navigation
                for (i in 0 until bar.childCount) {
                    val item = bar.getChildAt(i) as? LinearLayout ?: continue
                    val icon = (0 until item.childCount).map(item::getChildAt).firstOrNull { it is ImageView } ?: continue
                    icons += boundsInWindow(icon)
                }
            }
            settle(scenario, 600)
            assertEquals(4, icons.size)
            val image = capture(scenario)
            fun accentPixels(r: Rect): Int {
                var count = 0
                for (y in r.top until r.bottom) for (x in r.left until r.right) {
                    val c = image.getPixel(x, y)
                    if (Color.blue(c) - Color.red(c) > 90 && Color.blue(c) > 150) count++
                }
                return count
            }
            assertTrue("selected icon inside the pill is drawn in the accent", accentPixels(icons[0]) > 20)
            for (i in 1 until icons.size) assertEquals("unselected icon $i stays neutral", 0, accentPixels(icons[i]))
        }
    }

    private fun waitForBackend(scenario: ActivityScenario<PrismSandboxActivity>) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = it.card.diagnostics().let { d -> d.glassVisible && d.backdropBackend != LumenSurfaceBackend.STATIC } }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("glass never sampled its backdrop", ready)
        settle(scenario, 400)
    }

    private fun mix(base: Int, tint: Int): Int = (base + (tint - base) * 0.4f).toInt()

    private fun assertClose(message: String, expected: Int, actual: Int, tolerance: Int) {
        val ok = abs(Color.red(expected) - Color.red(actual)) <= tolerance && abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        assertTrue("$message expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}", ok)
    }

    @Suppress("unused")
    private fun ViewGroup.children(): List<View> = (0 until childCount).map(::getChildAt)
}
