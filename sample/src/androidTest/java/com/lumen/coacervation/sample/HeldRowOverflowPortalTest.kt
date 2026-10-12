package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.graphics.Rect
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.motion.modal.ModalCardRoot
import com.lumen.coacervation.engine.widget.LumenSlidingSelection
import android.widget.LinearLayout
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.interaction.ElasticMotionPolicy
import com.lumen.coacervation.engine.interaction.ElasticTravelPolicy
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel assertions include the source overlay; no ancestor's flags are relaxed. */
@RunWith(AndroidJUnit4::class)
class HeldRowOverflowPortalTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun managedNestedStretchViewportKeepsHorizontalOverflowAndVerticalContainment() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, 0, false, 0f, 320, nestedStretch = true) }
            awaitReady(scenario, fixture)
            scenario.onActivity {
                try {
                    assertTrue((fixture.viewport.parent as View).clipToOutline)
                    fixture.start()
                    fixture.drag(-100f)
                    assertTrue("The held row must travel left through the native nested viewport", fixture.row.translationX < -4f)
                    fixture.capture { bitmap ->
                        assertTrue("Only the held row is copied into panel padding", Color.green(bitmap.getPixel(16, 66)) > 80)
                        assertEquals("Other content remains vertically clipped", Color.WHITE, bitmap.getPixel(32, 124))
                        assertEquals("The panel background retains its rounded corner", Color.TRANSPARENT, bitmap.getPixel(1, 1))
                    }
                    fixture.controller.clear()
                    fixture.capture { assertEquals(Color.WHITE, it.getPixel(16, 66)) }
                    fixture.start()
                    fixture.drag(100f)
                    assertTrue(fixture.row.translationX > 4f)
                    fixture.capture { bitmap ->
                        assertTrue(Color.green(bitmap.getPixel(fixture.rightOverflowPixelX, 66)) > 80)
                    }
                } finally { fixture.close() }
            }
        }
    }

    @Test fun hardwareBufferContainsOnlyTheHeldControlOutsideViewport() {
        hardwareHorizontalOverflow(nestedStretch = false)
    }

    @Test fun hardwareRoundedNestedViewportCarriesOnlyTheHeldControlIntoPadding() {
        hardwareHorizontalOverflow(nestedStretch = true)
    }

    @Test fun hardwareSelectedBackdropFollowsTheHeldOptionOutsideRoundedViewport() {
        hardwareHorizontalOverflow(nestedStretch = true, selectedChoice = true)
    }

    @Test fun hardwareFourDirectionsPreserveMaterialForegroundAndHighlightThroughNestedViewports() {
        hardwareFourDirections(selectedChoice = false)
    }

    @Test fun hardwareSelectedBackdropAndReadableSourceOutlineFollowAllFourDirections() {
        hardwareFourDirections(selectedChoice = true)
    }

    private fun hardwareFourDirections(selectedChoice: Boolean) {
        val brightness = IntArray(2)
        for (pass in 0..1) ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            lateinit var activity: SurfaceSandboxActivity
            scenario.onActivity { owner ->
                activity = owner
                fixture = Fixture(owner, 0, true, if (pass == 0) 4f else 0f,
                    nestedStretch = true, selectedChoice = selectedChoice, fourDirections = true, multiNested = true)
            }
            awaitReady(scenario, fixture)
            scenario.onActivity { fixture.start() }
            SystemClock.sleep(220)
            try {
                val sourceBackground = fixture.row.background
                val foreground = fixture.row.foreground
                for ((dx, dy) in arrayOf(-100f to 0f, 100f to 0f, 0f to -100f, 0f to 100f)) {
                    scenario.onActivity {
                        fixture.drag(dx, dy)
                        assertTrue("The actual control must travel on the requested axis", if (dx == 0f)
                            kotlin.math.abs(fixture.row.translationY) > 4f else kotlin.math.abs(fixture.row.translationX) > 4f)
                        assertSame(sourceBackground, fixture.row.background)
                        assertSame(foreground, fixture.row.foreground)
                        assertTrue(fixture.row.clipToOutline)
                        fixture.assertFlags()
                    }
                    copyHardwareWindow(scenario, fixture, activity) { bitmap ->
                        val x = when { dx < 0f -> 16; dx > 0f -> fixture.rightOverflowPixelX; else -> fixture.panel.width / 2 }
                        val y = when { dy < 0f -> 16; dy > 0f -> 84; else -> 50 }
                        assertTrue("The source foreground must be visible beyond the intermediate clip on every axis", Color.green(bitmap.getPixel(x, y)) > 80)
                        assertEquals("The real outer rounded corner stays masked", Color.MAGENTA, bitmap.getPixel(1, 1))
                        assertEquals("Other hidden rows stay outside the native viewport", Color.WHITE, bitmap.getPixel(fixture.panel.width / 2, 96))
                        if (dx < 0f) for (yy in 42..58) for (xx in 14..18) {
                            val pixel = bitmap.getPixel(xx, yy)
                            brightness[pass] += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
                        }
                    }
                }
                scenario.onActivity { fixture.controller.clear(); fixture.assertFlags(resting = true) }
                copyHardwareWindow(scenario, fixture, activity) {
                    assertEquals(Color.WHITE, it.getPixel(16, 50))
                    assertEquals(Color.WHITE, it.getPixel(fixture.panel.width / 2, 16))
                }
            } finally { scenario.onActivity { fixture.close() } }
        }
        assertTrue("The actual touch highlight must affect overflow pixels on top of the real material", brightness[0] - brightness[1] > 17 * 5 * 3)
    }

    @Test fun baselineCropMovesWithoutRevealingHiddenPixelsAndForeignClipChangesArePreserved() = withCase(scroll = 32) { fixture ->
        val originalParent = fixture.row.parent
        val probe = Rect()
        fixture.drag(0f, -100f)
        assertTrue(fixture.row.getClipBounds(probe))
        assertEquals(16, probe.top)
        fixture.capture {
            assertTrue("Only the baseline-visible part may move above the viewport", Color.green(it.getPixel(160, 16)) > 80)
            assertEquals("The hidden top of the source is never copied", Color.WHITE, it.getPixel(160, 5))
        }
        fixture.drag(0f, 100f)
        fixture.capture { assertEquals("Native interior also keeps the baseline crop", Color.WHITE, it.getPixel(160, 24)) }
        fixture.drag(-100f, 0f)
        fixture.drag(100f, 0f)
        assertSame(originalParent, fixture.row.parent)
        assertTrue(fixture.content.clipChildren)
        assertTrue(fixture.content.clipToPadding)
        fixture.controller.clear()
        assertFalse("Owned crop is removed at clear", fixture.row.getClipBounds(probe))
        fixture.start()
        fixture.drag(-100f)
        val foreign = Rect(0, 24, fixture.row.width, 58)
        fixture.row.clipBounds = foreign
        fixture.drag(100f)
        assertTrue(fixture.row.getClipBounds(probe))
        assertEquals(foreign, probe)
        assertEquals("A foreign clip ends our transform ownership", 0f, fixture.row.translationX, 0f)
        fixture.controller.dispose()
        assertTrue(fixture.row.getClipBounds(probe))
        assertEquals(foreign, probe)
    }

    @Test fun changingARoundOutlineInsideTheSameProviderEndsThePortalWithoutOpeningNativeClips() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            var radius = 8f
            val provider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radius) }
            }
            scenario.onActivity {
                fixture = Fixture(it, 0, false, 0f)
                fixture.viewport.outlineProvider = provider
                fixture.viewport.clipToOutline = true
            }
            awaitReady(scenario, fixture)
            scenario.onActivity {
                try {
                    fixture.start()
                    fixture.drag(-100f)
                    assertTrue(fixture.row.translationX < -4f)
                    radius = 22f
                    fixture.viewport.invalidateOutline()
                    fixture.drag(100f)
                    assertEquals(0f, fixture.row.translationX, 0f)
                    assertEquals(1f, fixture.row.scaleX, 0f)
                    assertSame(provider, fixture.viewport.outlineProvider)
                    fixture.assertFlags(resting = true)
                    fixture.capture { bitmap -> assertEquals("No stale portal survives the outline change", Color.WHITE, bitmap.getPixel(16, 66)) }
                } finally { fixture.close() }
            }
        }
    }

    private fun copyHardwareWindow(scenario: ActivityScenario<SurfaceSandboxActivity>, fixture: Fixture,
                                   activity: SurfaceSandboxActivity, check: (Bitmap) -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        repeat(4) { attempt ->
            awaitHardwareFrame(scenario, fixture, deadline)
            val bitmap = Bitmap.createBitmap(fixture.panel.width, fixture.panel.height, Bitmap.Config.ARGB_8888)
            val complete = java.util.concurrent.CountDownLatch(1)
            val ownership = Any()
            var inFlight = false
            var abandoned = false
            var result = -1
            try {
                scenario.onActivity {
                    val origin = IntArray(2)
                    fixture.panel.getLocationInWindow(origin)
                    synchronized(ownership) { inFlight = true }
                    try {
                        android.view.PixelCopy.request(activity.window,
                            Rect(origin[0], origin[1], origin[0] + fixture.panel.width, origin[1] + fixture.panel.height), bitmap,
                            { value ->
                                synchronized(ownership) { result = value; inFlight = false; if (abandoned) bitmap.recycle() }
                                complete.countDown()
                            }, android.os.Handler(android.os.Looper.getMainLooper()))
                    } catch (failure: Throwable) {
                        synchronized(ownership) { inFlight = false }
                        throw failure
                    }
                }
                assertTrue(complete.await((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS))
                if (result == android.view.PixelCopy.ERROR_SOURCE_NO_DATA && attempt < 3 && SystemClock.uptimeMillis() < deadline) return@repeat
                assertEquals(android.view.PixelCopy.SUCCESS, result)
                check(bitmap)
                return
            } finally {
                synchronized(ownership) { abandoned = true; if (!inFlight && !bitmap.isRecycled) bitmap.recycle() }
            }
        }
    }

    private fun hardwareHorizontalOverflow(nestedStretch: Boolean, selectedChoice: Boolean = false) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            lateinit var activity: SurfaceSandboxActivity
            scenario.onActivity { owner -> activity = owner; fixture = Fixture(owner, 0, false, 0f, nestedStretch = nestedStretch, selectedChoice = selectedChoice) }
            awaitReady(scenario, fixture)
            try {
                scenario.onActivity { fixture.start() }
                SystemClock.sleep(220)
                scenario.onActivity { fixture.drag(-100f) }
                if (selectedChoice) scenario.onActivity {
                    assertTrue("The selected option must be the moving unit", fixture.row.translationX < -4f)
                }
                fun copyWindow(check: (Bitmap) -> Unit) {
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    repeat(4) { attempt ->
                        awaitHardwareFrame(scenario, fixture, deadline)
                        val bitmap = Bitmap.createBitmap(fixture.panel.width, fixture.panel.height, Bitmap.Config.ARGB_8888)
                        val complete = java.util.concurrent.CountDownLatch(1)
                        val ownership = Any()
                        var inFlight = false
                        var abandoned = false
                        var result = -1
                        try {
                            scenario.onActivity {
                                val origin = IntArray(2)
                                fixture.panel.getLocationInWindow(origin)
                                synchronized(ownership) { inFlight = true }
                                try {
                                    android.view.PixelCopy.request(activity.window,
                                        android.graphics.Rect(origin[0], origin[1], origin[0] + fixture.panel.width, origin[1] + fixture.panel.height), bitmap,
                                        { value ->
                                            synchronized(ownership) {
                                                result = value
                                                inFlight = false
                                                // A timeout cannot recycle a destination still owned by PixelCopy.
                                                if (abandoned) bitmap.recycle()
                                            }
                                            complete.countDown()
                                        }, android.os.Handler(android.os.Looper.getMainLooper()))
                                } catch (failure: Throwable) {
                                    synchronized(ownership) { inFlight = false }
                                    throw failure
                                }
                            }
                            val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
                            assertTrue("PixelCopy must complete within the shared deadline", complete.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS))
                            if (result == android.view.PixelCopy.ERROR_SOURCE_NO_DATA && attempt < 3 && SystemClock.uptimeMillis() < deadline) {
                                // Only a source with no queued buffer may retry, after another real frame.
                                return@repeat
                            }
                            assertEquals("PixelCopy must succeed after a committed frame (attempt ${attempt + 1})", android.view.PixelCopy.SUCCESS, result)
                            check(bitmap)
                            return
                        } finally {
                            synchronized(ownership) {
                                abandoned = true
                                if (!inFlight && !bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                    }
                }
                copyWindow { bitmap ->
                    assertTrue("The final hardware buffer must carry the overlay outside the viewport", Color.green(bitmap.getPixel(16, 66)) > 80)
                    assertEquals("The hardware interior must not be painted twice", Color.green(bitmap.getPixel(24, 66)).toFloat(), Color.green(bitmap.getPixel(16, 66)).toFloat(), 2f)
                    assertEquals("Other scrolled-out content must remain hidden", Color.WHITE, bitmap.getPixel(32, 124))
                }
                scenario.onActivity { fixture.controller.clear(); fixture.assertFlags(resting = true) }
                copyWindow { assertEquals("The final buffer must remove the portal after clear", Color.WHITE, it.getPixel(16, 66)) }
            } finally { scenario.onActivity { fixture.close() } }
        }
    }

    private fun awaitHardwareFrame(scenario: ActivityScenario<SurfaceSandboxActivity>, fixture: Fixture, deadline: Long) {
        val complete = java.util.concurrent.CountDownLatch(1)
        val committed = Runnable { complete.countDown() }
        var observer: ViewTreeObserver? = null
        var sawDraw = false
        val drawn = ViewTreeObserver.OnDrawListener {
            if (!sawDraw) {
                sawDraw = true
                // API 27/28 have no commit callback. Post after draw, then retry only NO_DATA.
                fixture.panel.post(committed)
            }
        }
        try {
            scenario.onActivity {
                assertTrue("The actual native window must use hardware acceleration", fixture.panel.isHardwareAccelerated)
                observer = fixture.panel.viewTreeObserver
                if (Build.VERSION.SDK_INT >= 29) observer!!.registerFrameCommitCallback(committed)
                else observer!!.addOnDrawListener(drawn)
                fixture.panel.postInvalidateOnAnimation()
            }
            val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
            assertTrue("A real hardware frame must finish within the shared deadline", complete.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            scenario.onActivity {
                fixture.panel.removeCallbacks(committed)
                observer?.takeIf { it.isAlive }?.let {
                    if (Build.VERSION.SDK_INT >= 29) it.unregisterFrameCommitCallback(committed)
                    else it.removeOnDrawListener(drawn)
                }
            }
        }
    }

    @Test fun anAlreadyUnclippedViewportDoesNotDrawADuplicateSource() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, 0, false, 0f) }
            awaitReady(scenario, fixture)
            scenario.onActivity {
                try {
                    fixture.viewport.clipChildren = false
                    fixture.viewport.clipToPadding = false
                    fixture.panel.clipChildren = false
                    fixture.panel.clipToPadding = false
                    fixture.start()
                    fixture.drag(-100f)
                    fixture.overlay.draws = 0
                    fixture.capture { bitmap ->
                        assertTrue(Color.green(bitmap.getPixel(16, 66)) > 80)
                        assertEquals("The native path already carries overflow", 1, fixture.overlay.draws)
                    }
                    assertFalse(fixture.viewport.clipChildren)
                    assertFalse(fixture.viewport.clipToPadding)
                    fixture.controller.clear()
                    assertFalse(fixture.viewport.clipChildren)
                    assertFalse(fixture.panel.clipChildren)
                } finally { fixture.close() }
            }
        }
    }

    @Test fun horizontalOverflowCarriesTheSourceOverlayWithoutDuplicatingInterior() {
        for (sceneWidth in listOf(320, Int.MAX_VALUE)) withCase(sceneWidth = sceneWidth) { fixture ->
            fixture.drag(-100f)
            assertTrue("A full-width row must visibly travel left: ${fixture.diagnostics()}", fixture.row.translationX < -4f)
            fixture.overlay.draws = 0
            fixture.capture { bitmap ->
                val outside = bitmap.getPixel(16, 66)
                val inside = bitmap.getPixel(24, 66)
                assertTrue("The source overlay must fill the overflow strip", Color.green(outside) > 80)
                assertEquals("Interior pixels must not be painted twice", Color.green(inside), Color.green(outside))
                assertEquals("Native interior and portal must each draw the same source overlay", 2, fixture.overlay.draws)
            }
            fixture.drag(100f)
            assertTrue("The same row must visibly travel right: ${fixture.diagnostics()}", fixture.row.translationX > 4f)
            fixture.capture { assertTrue(Color.green(it.getPixel(fixture.rightOverflowPixelX, 66)) > 80) }
            fixture.assertFlags()
        }
    }

    @Test fun realSurfaceAndTouchHighlightKeepTheirOriginalPositionThroughPortal() {
        val brightness = IntArray(2)
        for (index in 0..1) withCase(surface = true, glow = if (index == 0) 4f else 0f) { fixture ->
            fixture.drag(-100f)
            fixture.capture { bitmap ->
                for (y in 46..84) for (x in 14..19) {
                    val pixel = bitmap.getPixel(x, y)
                    brightness[index] += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
                }
            }
            assertTrue(fixture.positionNotifications > 0)
            assertSame("The source remains in the same content parent", fixture.content, fixture.row.parent)
            assertEquals(1f, fixture.row.alpha, 0f)
            assertTrue(fixture.row.overlay !== fixture.panel.overlay)
            fixture.assertFlags()
        }
        assertTrue("Real touch highlight must change pixels outside the viewport, compared with the same material without glow",
            brightness[0] - brightness[1] > 6 * 39 * 5)
    }

    @Test fun partiallyVisibleRowAndOtherContentKeepVerticalViewportClipping() = withCase(scroll = 32) { fixture ->
        fixture.drag(-100f)
        fixture.capture { bitmap ->
            assertTrue("The visible part may still use horizontal room", Color.green(bitmap.getPixel(16, 30)) > 80)
            assertEquals("The cropped top must not be revealed by the portal", Color.WHITE, bitmap.getPixel(16, 16))
            assertEquals("Other scrolled-out rows must not leak into panel padding", Color.WHITE, bitmap.getPixel(32, 124))
            assertEquals("Outer rounded corner remains masked", Color.TRANSPARENT, bitmap.getPixel(0, 0))
        }
        fixture.assertFlags()
    }

    @Test fun cancelDisposeAndClearRemoveOnlyTheHeldOverflow() {
        for (ending in 0..2) withCase { fixture ->
            fixture.drag(-100f)
            when (ending) {
                0 -> fixture.controller.clear()
                1 -> fixture.send(MotionEvent.ACTION_CANCEL, 0f)
                else -> fixture.controller.dispose()
            }
            fixture.capture { assertEquals("The old portal must be removed", Color.WHITE, it.getPixel(16, 66)) }
            assertEquals(0f, fixture.row.translationX, 0f)
            assertEquals(1f, fixture.row.scaleX, 0f)
            assertSame(fixture.content, fixture.row.parent)
            fixture.assertFlags(resting = true)
        }
    }

    @Test fun releaseAndRegrabDoNotDuplicateThePortal() {
        lateinit var fixture: Fixture
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { fixture = Fixture(it, 0, false, 0f) }
            awaitReady(scenario, fixture)
            scenario.onActivity {
                fixture.start()
                fixture.drag(-100f)
                fixture.send(MotionEvent.ACTION_UP, -100f)
                // Regrab while the release lease still owns the rendered geometry.
                fixture.start()
                fixture.drag(100f)
                fixture.overlay.draws = 0
                // Reversing from the left rebound brings this row inside its viewport.
                // Empty overflow must no longer draw the source a second time.
                fixture.capture { assertEquals(1, fixture.overlay.draws) }
                fixture.drag(-100f)
                assertTrue("A regrab still carries real overflow", fixture.row.translationX < -4f)
                fixture.overlay.draws = 0
                fixture.capture { assertEquals("Only one overflow copy is attached", 2, fixture.overlay.draws) }
                fixture.send(MotionEvent.ACTION_UP, -100f)
            }
            SystemClock.sleep(850)
            scenario.onActivity {
                try {
                    assertEquals(0f, fixture.row.translationX, 0f)
                    assertEquals(1f, fixture.row.scaleX, 0f)
                    fixture.capture { assertEquals(Color.WHITE, it.getPixel(fixture.rightOverflowPixelX, 66)) }
                    fixture.assertFlags(resting = true)
                } finally { fixture.close() }
            }
        }
    }

    private fun withCase(scroll: Int = 0, surface: Boolean = false, glow: Float = 0f, sceneWidth: Int = Int.MAX_VALUE, check: (Fixture) -> Unit) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, scroll, surface, glow, sceneWidth) }
            awaitReady(scenario, fixture)
            scenario.onActivity { fixture.start() }
            // Let real Choreographer press frames run; a virtual event deadline
            // alone does not prove that the touch highlight was ever visible.
            SystemClock.sleep(220)
            scenario.onActivity {
                try { check(fixture) } finally { fixture.close() }
            }
        }
    }

    private fun awaitReady(scenario: ActivityScenario<SurfaceSandboxActivity>, fixture: Fixture) {
        val end = SystemClock.uptimeMillis() + 5000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < end) {
            scenario.onActivity { ready = fixture.ready() }
            if (!ready) SystemClock.sleep(20)
        }
        assertTrue("Fixture must finish a real layout before DOWN: ${fixture.diagnostics()}", ready)
    }

    private class Fixture(activity: SurfaceSandboxActivity, scroll: Int, surface: Boolean, glow: Float,
                          sceneWidthLimit: Int = Int.MAX_VALUE, nestedStretch: Boolean = false,
                          selectedChoice: Boolean = false, fourDirections: Boolean = false,
                          multiNested: Boolean = false) {
        private val root = activity.window.decorView
        private val windowWidth = root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        private val sceneWidth = minOf(windowWidth, sceneWidthLimit)
        private val panelWidth = minOf(340, sceneWidth)
        private val contentWidth = panelWidth - 40
        private val panelLeft = minOf(80, (sceneWidth - panelWidth) / 2)
        val rightOverflowPixelX: Int get() = viewport.right + 4
        val panel = FrameLayout(activity).apply {
            tag = ElasticInteractionController.CONTAINER_TAG
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16f }
            clipToOutline = true
        }
        val viewport = if (nestedStretch) NestedScrollView(activity) else ScrollView(activity)
        val managedAncestors = ArrayList<ViewGroup>()
        val content = FrameLayout(activity).apply { minimumHeight = 400 }
        val row = FrameLayout(activity).apply {
            isClickable = true
            background = GradientDrawable().apply { setColor(Color.BLUE); cornerRadius = 16f }
        }
        val overlay = CountingOverlay()
        val controller: ElasticInteractionController
        var positionNotifications = 0
        private val material = LumenActivityDelegate(activity, { LumenPalette.neutral(false) })
        private val scrollY = scroll
        private val location = IntArray(2)
        private var down = 0L
        private var sent = 0L
        private var cancels = 0
        private val touchX = if (sceneWidthLimit == 320) contentWidth / 2f else 100f

        init {
            activity.binding.close()
            val rowTop = if (fourDirections) 0 else 16
            val viewportHeight = if (fourDirections) 60 else 100
            val rowHost = if (selectedChoice) LumenSlidingSelection(activity, ColorDrawable(Color.BLUE)).apply {
                row.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 16f }
                addOption(row, LinearLayout.LayoutParams(contentWidth, 60))
                addOption(View(activity), LinearLayout.LayoutParams(contentWidth, 60))
            } else row
            content.addView(rowHost, FrameLayout.LayoutParams(contentWidth, if (selectedChoice) -2 else 60).apply { topMargin = rowTop })
            content.addView(View(activity).apply { setBackgroundColor(Color.RED) },
                FrameLayout.LayoutParams(contentWidth, 80).apply { topMargin = 180 })
            viewport.addView(content, FrameLayout.LayoutParams(contentWidth, 400))
            panel.addView(viewport, FrameLayout.LayoutParams(contentWidth, viewportHeight))
            if (nestedStretch) {
                assertNotNull(LiquidStretchViewport.installAround(viewport, { true }) { _, _ -> })
            }
            managedAncestors.add(viewport)
            (viewport.parent as? LiquidStretchViewport)?.let { managedAncestors.add(it) }
            if (multiNested) {
                val inline = panel.getChildAt(0)
                panel.removeView(inline)
                val outerContent = FrameLayout(activity).apply {
                    addView(inline, FrameLayout.LayoutParams(contentWidth, viewportHeight))
                    minimumHeight = 400
                }
                val outer = NestedScrollView(activity).apply { addView(outerContent) }
                panel.addView(outer, FrameLayout.LayoutParams(contentWidth, viewportHeight))
                managedAncestors.add(outerContent)
                managedAncestors.add(outer)
                LiquidStretchViewport.installAround(outer, { true }) { _, _ -> }?.let { managedAncestors.add(it) }
            }
            if (fourDirections) {
                // Explicit rectangular inline viewports retain the host's intended shape.
                // The actual panel and the moving control have their own real rounded outlines.
                val square = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setRect(0, 0, view.width, view.height) }
                }
                for (ancestor in managedAncestors) if (ancestor is ScrollView || ancestor is NestedScrollView || ancestor is LiquidStretchViewport) {
                    ancestor.outlineProvider = square
                    ancestor.clipToOutline = true
                }
                row.clipToOutline = true
            }
            val windowBody = if (nestedStretch) ModalCardRoot(activity) else FrameLayout(activity)
            if (fourDirections) windowBody.setBackgroundColor(Color.MAGENTA)
            activity.setContentView(windowBody.apply {
                addView(panel, FrameLayout.LayoutParams(panelWidth, viewportHeight + 40).apply { leftMargin = panelLeft; topMargin = 80 })
            }, ViewGroup.LayoutParams(sceneWidth, ViewGroup.LayoutParams.MATCH_PARENT))
            if (surface) {
                material.prepare()
                assertTrue(material.isPrepared)
                assertTrue(material.bindRoot(activity.window.decorView))
                material.onStart()
                if (selectedChoice) (rowHost as LumenSlidingSelection).indicator.background = material.surface(Color.BLACK, 16f, SurfaceRole.SELECTED_ITEM)
                else row.background = material.surface(Color.BLACK, 16f, SurfaceRole.FILLED_BUTTON)
            }
            overlay.setBounds(0, 0, contentWidth, 60)
            if (fourDirections) row.foreground = overlay else if (!surface) row.overlay.add(overlay)
            controller = ElasticInteractionController(activity.window.decorView, notifyPositionChanged = {
                positionNotifications++
                material.notifyPositionChanged()
            }, effectTuning = { LumenEffectTuning(dragGlowIntensity = glow) }).apply {
                travelPolicy = ElasticTravelPolicy.PARENT_BOUNDS
            }
        }

        fun ready(): Boolean = root.hasWindowFocus() && panel.width == panelWidth && row.width == contentWidth &&
            row.height == 60 && row.isLaidOut && !row.isLayoutRequested && !panel.isLayoutRequested

        fun start() {
            assertTrue("DOWN must start after layout: ${diagnostics()}", ready())
            assertTrue("Fixture panel must fit the window: ${diagnostics()}", panel.left >= 0 && panel.right <= sceneWidth && sceneWidth <= root.width)
            viewport.scrollTo(0, scrollY)
            row.getLocationOnScreen(location)
            down = SystemClock.uptimeMillis()
            sent = down
            send(MotionEvent.ACTION_DOWN, 0f)
        }

        fun drag(dx: Float, dy: Float = 0f) {
            send(MotionEvent.ACTION_MOVE, dx, dy)
            assertTrue("The actual held drag must be captured: ${diagnostics()}", cancels > 0)
        }

        fun send(action: Int, dx: Float, dy: Float = 0f) {
            sent = if (action == MotionEvent.ACTION_DOWN) down else maxOf(sent + 1, maxOf(down + ElasticMotionPolicy.HOLD_MILLIS + 1, SystemClock.uptimeMillis()))
            val event = MotionEvent.obtain(down, sent, action, location[0] + touchX + dx, location[1] + 50f + dy, 0)
            try {
                controller.dispatch(event) {
                    if (it.actionMasked == MotionEvent.ACTION_DOWN) row.isPressed = true
                    if (it.actionMasked == MotionEvent.ACTION_CANCEL) { row.isPressed = false; cancels++ }
                    true
                }
            } finally { event.recycle() }
        }

        fun capture(check: (Bitmap) -> Unit) {
            val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
            try { panel.draw(Canvas(bitmap)); check(bitmap) } finally { bitmap.recycle() }
        }

        fun assertFlags(resting: Boolean = false) {
            assertTrue(viewport.clipChildren)
            assertTrue(viewport.clipToPadding)
            assertTrue(panel.clipChildren)
            assertTrue(panel.clipToPadding)
            assertTrue(panel.clipToOutline)
            for (ancestor in managedAncestors) { assertTrue(ancestor.clipChildren); assertTrue(ancestor.clipToPadding) }
            assertEquals(1f, viewport.scaleX, 0f)
            assertEquals(1f, row.alpha, 0f)
            if (resting) { assertTrue(content.clipChildren); assertTrue(content.clipToPadding) }
        }

        fun diagnostics(): String = "window=${root.width}x${root.height},scene=$sceneWidth,panel=${panel.left}..${panel.right}," +
            "row=${row.width}x${row.height},tx=${row.translationX},sx=${row.scaleX},pivot=${row.pivotX}," +
            "down=$down,event=$sent,now=${SystemClock.uptimeMillis()},cancels=$cancels,overlayDraws=${overlay.draws}," +
            "focus=${root.hasWindowFocus()},location=${location[0]}:${location[1]},touchX=$touchX,pressed=${row.isPressed}," +
            "transient=${row.hasTransientState()},layoutRequested=${row.isLayoutRequested},density=${root.resources.displayMetrics.density}," +
            "leftGestureInset=${if (android.os.Build.VERSION.SDK_INT >= 29) root.rootWindowInsets?.systemGestureInsets?.left else 0}"

        fun close() { controller.dispose(); material.onDestroy() }
    }

    private class CountingOverlay : Drawable() {
        var draws = 0
        override fun draw(canvas: Canvas) { draws++; canvas.drawColor(0x8044FF44.toInt()) }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
