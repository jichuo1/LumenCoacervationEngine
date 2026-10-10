package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
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
    @Test fun hardwareBufferContainsOnlyTheHeldControlOutsideViewport() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            lateinit var activity: SurfaceSandboxActivity
            scenario.onActivity { owner -> activity = owner; fixture = Fixture(owner, 0, false, 0f) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            try {
                scenario.onActivity { fixture.start() }
                SystemClock.sleep(220)
                scenario.onActivity { fixture.drag(-100f) }
                SystemClock.sleep(120)
                fun copyWindow(check: (Bitmap) -> Unit) {
                    val bitmap = Bitmap.createBitmap(340, 140, Bitmap.Config.ARGB_8888)
                    val complete = java.util.concurrent.CountDownLatch(1)
                    var result = -1
                    scenario.onActivity {
                        assertTrue("The actual native window must use hardware acceleration", fixture.panel.isHardwareAccelerated)
                        val origin = IntArray(2)
                        fixture.panel.getLocationInWindow(origin)
                        android.view.PixelCopy.request(activity.window,
                            android.graphics.Rect(origin[0], origin[1], origin[0] + 340, origin[1] + 140), bitmap,
                            { value -> result = value; complete.countDown() },
                            android.os.Handler(android.os.Looper.getMainLooper()))
                    }
                    try {
                        assertTrue("PixelCopy must complete", complete.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        assertEquals(android.view.PixelCopy.SUCCESS, result)
                        check(bitmap)
                    } finally { bitmap.recycle() }
                }
                copyWindow { bitmap ->
                    assertTrue("The final hardware buffer must carry the overlay outside the viewport", Color.green(bitmap.getPixel(16, 66)) > 80)
                    assertEquals("The hardware interior must not be painted twice", Color.green(bitmap.getPixel(24, 66)).toFloat(), Color.green(bitmap.getPixel(16, 66)).toFloat(), 2f)
                    assertEquals("Other scrolled-out content must remain hidden", Color.WHITE, bitmap.getPixel(32, 124))
                }
                scenario.onActivity { fixture.controller.clear(); fixture.assertFlags(resting = true) }
                SystemClock.sleep(120)
                copyWindow { assertEquals("The final buffer must remove the portal after clear", Color.WHITE, it.getPixel(16, 66)) }
            } finally { scenario.onActivity { fixture.close() } }
        }
    }

    @Test fun anAlreadyUnclippedViewportDoesNotDrawADuplicateSource() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, 0, false, 0f) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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

    @Test fun horizontalOverflowCarriesTheSourceOverlayWithoutDuplicatingInterior() = withCase { fixture ->
        fixture.drag(-100f)
        assertTrue("A full-width row must visibly travel left", fixture.row.translationX < -4f)
        fixture.overlay.draws = 0
        fixture.capture { bitmap ->
            val outside = bitmap.getPixel(16, 66)
            val inside = bitmap.getPixel(24, 66)
            assertTrue("The source overlay must fill the overflow strip", Color.green(outside) > 80)
            assertEquals("Interior pixels must not be painted twice", Color.green(inside), Color.green(outside))
            assertEquals("Native interior and portal must each draw the same source overlay", 2, fixture.overlay.draws)
        }
        fixture.drag(100f)
        assertTrue("The same row must visibly travel right", fixture.row.translationX > 4f)
        fixture.capture { assertTrue(Color.green(it.getPixel(324, 66)) > 80) }
        fixture.assertFlags()
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
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                fixture.start()
                fixture.drag(-100f)
                fixture.send(MotionEvent.ACTION_UP, -100f)
                // Regrab while the release lease still owns the rendered geometry.
                fixture.start()
                fixture.drag(100f)
                fixture.overlay.draws = 0
                fixture.capture { assertEquals(2, fixture.overlay.draws) }
                fixture.send(MotionEvent.ACTION_UP, 100f)
            }
            SystemClock.sleep(850)
            scenario.onActivity {
                try {
                    assertEquals(0f, fixture.row.translationX, 0f)
                    assertEquals(1f, fixture.row.scaleX, 0f)
                    fixture.capture { assertEquals(Color.WHITE, it.getPixel(324, 66)) }
                    fixture.assertFlags(resting = true)
                } finally { fixture.close() }
            }
        }
    }

    private fun withCase(scroll: Int = 0, surface: Boolean = false, glow: Float = 0f, check: (Fixture) -> Unit) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, scroll, surface, glow) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { fixture.start() }
            // Let real Choreographer press frames run; a virtual event deadline
            // alone does not prove that the touch highlight was ever visible.
            SystemClock.sleep(220)
            scenario.onActivity {
                try { check(fixture) } finally { fixture.close() }
            }
        }
    }

    private class Fixture(activity: SurfaceSandboxActivity, scroll: Int, surface: Boolean, glow: Float) {
        val panel = FrameLayout(activity).apply {
            tag = ElasticInteractionController.CONTAINER_TAG
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16f }
            clipToOutline = true
        }
        val viewport = ScrollView(activity)
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

        init {
            activity.binding.close()
            content.addView(row, FrameLayout.LayoutParams(300, 60).apply { topMargin = 16 })
            content.addView(View(activity).apply { setBackgroundColor(Color.RED) },
                FrameLayout.LayoutParams(300, 80).apply { topMargin = 180 })
            viewport.addView(content, FrameLayout.LayoutParams(300, 400))
            panel.addView(viewport, FrameLayout.LayoutParams(300, 100))
            activity.setContentView(FrameLayout(activity).apply {
                addView(panel, FrameLayout.LayoutParams(340, 140).apply { leftMargin = 80; topMargin = 80 })
            })
            if (surface) {
                material.prepare()
                assertTrue(material.isPrepared)
                assertTrue(material.bindRoot(activity.window.decorView))
                material.onStart()
                row.background = material.surface(Color.BLACK, 16f, SurfaceRole.FILLED_BUTTON)
            }
            overlay.setBounds(0, 0, 300, 60)
            if (!surface) row.overlay.add(overlay)
            controller = ElasticInteractionController(activity.window.decorView, notifyPositionChanged = {
                positionNotifications++
                material.notifyPositionChanged()
            }, effectTuning = { LumenEffectTuning(dragGlowIntensity = glow) }).apply {
                travelPolicy = ElasticTravelPolicy.PARENT_BOUNDS
            }
        }

        fun start() {
            viewport.scrollTo(0, scrollY)
            row.getLocationOnScreen(location)
            down = SystemClock.uptimeMillis()
            sent = down
            send(MotionEvent.ACTION_DOWN, 0f)
        }

        fun drag(dx: Float) {
            send(MotionEvent.ACTION_MOVE, dx)
            assertTrue("The actual held drag must be captured", cancels > 0)
        }

        fun send(action: Int, dx: Float) {
            sent = if (action == MotionEvent.ACTION_DOWN) down else maxOf(sent + 1, down + ElasticMotionPolicy.HOLD_MILLIS + 1)
            val event = MotionEvent.obtain(down, sent, action, location[0] + 100f + dx, location[1] + 50f, 0)
            try {
                controller.dispatch(event) {
                    if (it.actionMasked == MotionEvent.ACTION_DOWN) row.isPressed = true
                    if (it.actionMasked == MotionEvent.ACTION_CANCEL) { row.isPressed = false; cancels++ }
                    true
                }
            } finally { event.recycle() }
        }

        fun capture(check: (Bitmap) -> Unit) {
            val bitmap = Bitmap.createBitmap(340, 140, Bitmap.Config.ARGB_8888)
            try { panel.draw(Canvas(bitmap)); check(bitmap) } finally { bitmap.recycle() }
        }

        fun assertFlags(resting: Boolean = false) {
            assertTrue(viewport.clipChildren)
            assertTrue(viewport.clipToPadding)
            assertTrue(panel.clipChildren)
            assertTrue(panel.clipToPadding)
            assertTrue(panel.clipToOutline)
            assertEquals(1f, viewport.scaleX, 0f)
            assertEquals(1f, row.alpha, 0f)
            if (resting) { assertTrue(content.clipChildren); assertTrue(content.clipToPadding) }
        }

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
