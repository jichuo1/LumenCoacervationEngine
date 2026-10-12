package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lumen.coacervation.engine.host.LumenLocalPressOptions
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceCorners
import com.lumen.coacervation.engine.host.LumenSurfaceEnhancements
import com.lumen.coacervation.engine.host.LumenSurfaceGeometryOptions
import com.lumen.coacervation.engine.host.LumenSurfaceLightOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native node identity, actual Window pixels, and bounded idle capture in one Composition. */
@RunWith(AndroidJUnit4::class)
class NativeComposeIntegrationTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()

    private fun await(scenario: ActivityScenario<ComposeSandboxActivity>, message: String,
        condition: (ComposeSandboxActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { ready = condition(it) }
            if (!ready) SystemClock.sleep(25)
        }
        assertTrue(message, ready)
    }

    private fun ready(scenario: ActivityScenario<ComposeSandboxActivity>) {
        val expected = if (Build.VERSION.SDK_INT >= 31) LumenSurfaceBackend.GPU else LumenSurfaceBackend.SOFTWARE
        await(scenario, "Native surfaces did not sample the Compose backdrop") { a ->
            a.stateReady && a.composeView.hasWindowFocus() && a.composeView.width > 0 &&
                listOf(ComposeSandboxActivity.CARD, ComposeSandboxActivity.INPUT, ComposeSandboxActivity.SELECTION)
                    .all { a.lumenState.bindingFor(it)?.diagnostics()?.backend == expected } &&
                a.lumenState.session.diagnostics().attachedSurfaces >= 4
        }
    }

    private fun image(scenario: ActivityScenario<ComposeSandboxActivity>): Bitmap {
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var code = -1
        scenario.onActivity { a ->
            bitmap = Bitmap.createBitmap(a.window.decorView.width, a.window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(a.window, bitmap!!, { code = it; latch.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("PixelCopy did not complete", latch.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, code)
        return bitmap!!
    }

    private fun bounds(scenario: ActivityScenario<ComposeSandboxActivity>, key: String): Rect {
        var value: Rect? = null
        scenario.onActivity { a ->
            val r = a.surfaceBounds.getValue(key)
            value = Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
        }
        return value!!
    }

    private fun viewCount(view: View): Int = 1 + if (view is ViewGroup) (0 until view.childCount).sumOf { viewCount(view.getChildAt(it)) } else 0
    private fun composeViewCount(view: View): Int = (if (view is ComposeView) 1 else 0) +
        if (view is ViewGroup) (0 until view.childCount).sumOf { composeViewCount(view.getChildAt(it)) } else 0

    private fun pointer(down: Long, action: Int, x: Float, y: Float,
        eventTime: Long = SystemClock.uptimeMillis()) {
        val event = MotionEvent.obtain(down, eventTime, action, x, y, 0)
        try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event) }
        finally { event.recycle() }
    }

    @Test fun heldHorizontalDragMovesMaterialAndContentAndReleasesWithoutClicking() {
        ActivityScenario.launch(ComposeSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            var origin = Offset.Zero
            var center = Offset.Zero
            var density = 1f
            scenario.onActivity { a ->
                val c = a.surfaceCoordinates.getValue(ComposeSandboxActivity.CARD)
                origin = c.localToScreen(Offset.Zero)
                center = c.localToScreen(Offset(c.size.width / 2f, c.size.height / 2f))
                density = a.resources.displayMetrics.density
            }
            val down = SystemClock.uptimeMillis()
            pointer(down, MotionEvent.ACTION_DOWN, center.x, center.y)
            try {
                SystemClock.sleep(220)
                pointer(down, MotionEvent.ACTION_MOVE, center.x + 100 * density, center.y)
                await(scenario, "Held horizontal drag stayed at the original position") { a ->
                    a.surfaceCoordinates.getValue(ComposeSandboxActivity.CARD).localToScreen(Offset.Zero).x > origin.x + 3 * density
                }
                scenario.onActivity { a ->
                    assertEquals(if (Build.VERSION.SDK_INT >= 31) LumenSurfaceBackend.GPU else LumenSurfaceBackend.SOFTWARE,
                        a.lumenState.bindingFor(ComposeSandboxActivity.CARD)?.diagnostics()?.backend)
                }
            } finally { pointer(down, MotionEvent.ACTION_UP, center.x + 100 * density, center.y) }
            await(scenario, "Released elastic layer did not settle") { a ->
                abs(a.surfaceCoordinates.getValue(ComposeSandboxActivity.CARD).localToScreen(Offset.Zero).x - origin.x) < density * .2f
            }
            scenario.onActivity { assertEquals("Captured drag became a business click", 0, it.clickCount) }
        }
    }

    @Test fun anEarlySlopCrossingCannotBecomeAHoldLaterInTheSameStream() {
        ActivityScenario.launch(ComposeSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            var origin = Offset.Zero
            var center = Offset.Zero
            var density = 1f
            scenario.onActivity { a ->
                val c = a.surfaceCoordinates.getValue(ComposeSandboxActivity.CARD)
                origin = c.localToScreen(Offset.Zero)
                center = c.localToScreen(Offset(c.size.width / 2f, c.size.height / 2f))
                density = a.resources.displayMetrics.density
            }
            val down = SystemClock.uptimeMillis()
            pointer(down, MotionEvent.ACTION_DOWN, center.x, center.y, down)
            try {
                // Input injection may block longer than the hold threshold on an emulator.
                // Preserve the gesture's early movement timestamp independently of delivery time.
                pointer(down, MotionEvent.ACTION_MOVE, center.x + 30 * density, center.y, down + 16L)
                SystemClock.sleep(220)
                pointer(down, MotionEvent.ACTION_MOVE, center.x + 60 * density, center.y)
                SystemClock.sleep(80)
                scenario.onActivity { a ->
                    assertEquals(origin.x.toDouble(), a.surfaceCoordinates.getValue(ComposeSandboxActivity.CARD)
                        .localToScreen(Offset.Zero).x.toDouble(), density.toDouble() * .2)
                }
            } finally { pointer(down, MotionEvent.ACTION_UP, center.x + 60 * density, center.y) }
        }
    }

    @Test fun independentBindingsShareOneComposeRootAndIdleSourceStopsRecording() {
        ActivityScenario.launch(ComposeSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { a ->
                val keys = listOf(ComposeSandboxActivity.CARD, ComposeSandboxActivity.INPUT, ComposeSandboxActivity.SELECTION)
                val bindings = keys.map { a.lumenState.bindingFor(it)!! }
                assertEquals(3, bindings.map { it.id }.toSet().size)
                assertEquals(1, composeViewCount(a.composeView))
                assertTrue("A View was allocated for every native surface",
                    viewCount(a.composeView) < a.lumenState.session.diagnostics().attachedSurfaces)
                assertTrue(a.lumenState.session.diagnostics().contentRecordings > 0)
                assertTrue(bindings.all { it.diagnostics()?.hasVisibleFrame == true })
            }
            SystemClock.sleep(200)
            var recordings = 0L
            scenario.onActivity { recordings = it.lumenState.session.diagnostics().contentRecordings }
            SystemClock.sleep(400)
            scenario.onActivity {
                assertEquals("A static Compose backdrop kept recording", recordings,
                    it.lumenState.session.diagnostics().contentRecordings)
            }
        }
    }

    @Test fun aSurfaceInsideTheBackdropCannotFeedBackIntoItsOwnSource() {
        ActivityScenario.launch(ComposeSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { it.decorateBackdrop = true }
            SystemClock.sleep(200)
            ready(scenario)
            scenario.onActivity { assertNull(it.lumenState.bindingFor("backdrop-surface")) }
            var recordings = 0L
            scenario.onActivity { recordings = it.lumenState.session.diagnostics().contentRecordings }
            SystemClock.sleep(300)
            scenario.onActivity { assertEquals(recordings, it.lumenState.session.diagnostics().contentRecordings) }
        }
    }

    @Test fun movingACardSamplesTheNewPositionWithoutMovingTheBackdrop() {
        ActivityScenario.launch(ComposeSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            val beforeBounds = bounds(scenario, ComposeSandboxActivity.CARD)
            val before = image(scenario)
            scenario.onActivity { it.cardOffsetDp = 24f }
            await(scenario, "Card coordinates did not move") {
                val r = it.surfaceBounds[ComposeSandboxActivity.CARD]
                r != null && r.left > beforeBounds.left + 10
            }
            ready(scenario)
            SystemClock.sleep(100)
            val afterBounds = bounds(scenario, ComposeSandboxActivity.CARD)
            val after = image(scenario)
            var density = 1f
            scenario.onActivity { density = it.resources.displayMetrics.density }
            // Below the title and away from rounded corners. A 24dp move swaps the red/blue stripe.
            val x = (28f * density).roundToInt()
            val y = (58f * density).roundToInt()
            val original = before.getPixel(beforeBounds.left + x, beforeBounds.top + y)
            val moved = after.getPixel(afterBounds.left + x, afterBounds.top + y)
            assertTrue("Sampling stayed at the old card position: $original / $moved",
                (Color.red(original) - Color.blue(original)) * (Color.red(moved) - Color.blue(moved)) < 0)
            assertEquals("Card motion changed source pixels outside the card",
                before.getPixel(4, beforeBounds.top + y), after.getPixel(4, afterBounds.top + y))
            before.recycle(); after.recycle()
        }
    }

    @Test fun changingSelectionRestoresTheOldBackgroundAndMovesItsOwnSample() {
        ActivityScenario.launch(ComposeSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            val old = bounds(scenario, ComposeSandboxActivity.SELECTION)
            scenario.onActivity { it.showSelectionIndicator = false }
            await(scenario, "Selection node did not detach") { it.lumenState.bindingFor(ComposeSandboxActivity.SELECTION) == null }
            SystemClock.sleep(100)
            val baseline = image(scenario)
            scenario.onActivity { it.showSelectionIndicator = true }
            ready(scenario)
            SystemClock.sleep(100)
            val selected = image(scenario)
            scenario.onActivity { it.selectedIndex = 2 }
            await(scenario, "Selection geometry did not move") {
                (it.surfaceBounds[ComposeSandboxActivity.SELECTION]?.left ?: 0f) > old.right
            }
            ready(scenario)
            SystemClock.sleep(100)
            val moved = image(scenario)
            var density = 1f
            scenario.onActivity { density = it.resources.displayMetrics.density }
            val left = old.left + old.width() / 3
            val top = old.top + (10f * density).roundToInt()
            var selectedDifference = 0L
            var staleDifference = 0L
            for (y in top until top + 4) for (x in left until left + 8) {
                val base = baseline.getPixel(x, y)
                selectedDifference += abs(Color.red(selected.getPixel(x, y)) - Color.red(base)) +
                    abs(Color.blue(selected.getPixel(x, y)) - Color.blue(base))
                staleDifference += abs(Color.red(moved.getPixel(x, y)) - Color.red(base)) +
                    abs(Color.blue(moved.getPixel(x, y)) - Color.blue(base))
            }
            assertTrue("Selected native background did not draw", selectedDifference > 500)
            assertTrue("The old selection retained a stale background: $staleDifference", staleDifference <= 64)
            baseline.recycle(); selected.recycle(); moved.recycle()
        }
    }

    @Test fun lazyKeyReplacementAndRemovingTheCompositionReleaseEveryBinding() {
        ActivityScenario.launch(ComposeSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            var oldIds = emptySet<Long>()
            var views = 0
            scenario.onActivity { a ->
                oldIds = a.surfaceBounds.keys.filter { it.startsWith("row-0-") }
                    .mapNotNull { a.lumenState.bindingFor(it)?.id }.toSet()
                assertTrue(oldIds.isNotEmpty())
                views = viewCount(a.composeView)
                a.requestedRowIndex = 40
                a.rowGeneration = 1
            }
            await(scenario, "Lazy composition did not use the replacement keys") { a ->
                a.surfaceBounds.keys.any { it.startsWith("row-1-40") } &&
                    a.surfaceBounds.keys.filter { it.startsWith("row-0-") }.all { a.lumenState.bindingFor(it) == null }
            }
            scenario.onActivity { a ->
                val replacements = a.surfaceBounds.keys.filter { it.startsWith("row-1-") }
                    .mapNotNull { a.lumenState.bindingFor(it)?.id }.toSet()
                assertTrue(replacements.isNotEmpty())
                assertTrue("Lazy node reuse kept the retired surface identity", replacements.intersect(oldIds).isEmpty())
                assertEquals(views, viewCount(a.composeView))
                a.showSurfaces = false
            }
            await(scenario, "Detached Compose nodes retained surface bindings") {
                it.lumenState.session.diagnostics().attachedSurfaces == 0
            }
            scenario.onActivity { a ->
                assertNull(a.lumenState.bindingFor(ComposeSandboxActivity.INPUT))
                a.showSurfaces = true
            }
            ready(scenario)
            scenario.onActivity { assertTrue(it.lumenState.session.diagnostics().attachedSurfaces >= 4) }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 33)
    fun nativeEnhancedPressChangesPixelsAndUniformUpdatesKeepTheShaderCache() {
        ActivityScenario.launch(ComposeSandboxActivity::class.java).use { scenario ->
            ready(scenario)
            scenario.onActivity { a ->
                a.enhancements = LumenSurfaceEnhancements(
                    geometry = LumenSurfaceGeometryOptions(cornersEnabled = true,
                        corners = LumenSurfaceCorners(28f, 4f, 18f, 8f)),
                    press = LumenLocalPressOptions(enabled = true, displacementDp = 8f,
                        radiusFraction = .18f, highlightStrength = 0f),
                    light = LumenSurfaceLightOptions(enabled = true, intensity = .4f))
            }
            await(scenario, "Native enhanced surfaces did not create their direct shader inputs") { a ->
                a.lumenState.session.performanceDiagnostics().runtimeShaderBuilds >= 3 &&
                    listOf(ComposeSandboxActivity.CARD, ComposeSandboxActivity.INPUT, ComposeSandboxActivity.SELECTION)
                        .all { a.lumenState.bindingFor(it)?.diagnostics()?.backend == LumenSurfaceBackend.GPU }
            }
            SystemClock.sleep(150)
            val card = bounds(scenario, ComposeSandboxActivity.CARD)
            val rest = image(scenario)
            var shaders = 0L
            var effects = 0L
            scenario.onActivity { a ->
                val p = a.lumenState.session.performanceDiagnostics()
                shaders = p.runtimeShaderBuilds; effects = p.effectChainBuilds
                a.lumenState.bindingFor(ComposeSandboxActivity.CARD)!!.setPressPixels(card.width() * .45f, card.height() * .75f, 1f)
            }
            SystemClock.sleep(150)
            val pressed = image(scenario)
            var difference = 0L
            for (y in card.top + card.height() / 2 until card.bottom - 12)
                for (x in card.left + card.width() / 3 until card.left + card.width() * 3 / 5) {
                    difference += abs(Color.red(rest.getPixel(x, y)) - Color.red(pressed.getPixel(x, y)))
                }
            assertTrue("Native Compose press did not alter sampled pixels", difference > 1000)
            scenario.onActivity { a ->
                val binding = a.lumenState.bindingFor(ComposeSandboxActivity.CARD)!!
                binding.setPressPixels(card.width() * .45f, card.height() * .75f, .5f)
                binding.setLightDirection(.4f, -.2f, .8f)
            }
            SystemClock.sleep(100)
            scenario.onActivity { a ->
                val p = a.lumenState.session.performanceDiagnostics()
                assertEquals(shaders, p.runtimeShaderBuilds)
                assertEquals(effects, p.effectChainBuilds)
                assertEquals(LumenSurfaceBackend.GPU, a.lumenState.bindingFor(ComposeSandboxActivity.CARD)?.diagnostics()?.backend)
            }
            rest.recycle(); pressed.recycle()
        }
    }
}
