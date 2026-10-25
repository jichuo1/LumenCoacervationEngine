package com.lumen.coacervation.sample

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.widget.LumenSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * LumenSlider on a real window with the engine's long-press elastic installed: gesture ownership, vertical
 * yield to the scroll view, tap / RTL mapping, discrete snapping, keyboard and accessibility, controller
 * cleanup, the HDR path and the capsule fill pixels.
 */
@RunWith(AndroidJUnit4::class)
class LumenSliderIntegrationTest {
    @get:Rule val hardwareOutput = HardwareOutputRule()

    private fun launch(block: (ActivityScenario<SliderSandboxActivity>, SliderSandboxActivity) -> Unit) {
        ActivityScenario.launch(SliderSandboxActivity::class.java).use { scenario ->
            lateinit var activity: SliderSandboxActivity
            scenario.onActivity { activity = it }
            WindowRegressionCapture.frame(activity.root)
            block(scenario, activity)
        }
    }

    private fun bringIntoView(activity: SliderSandboxActivity, slider: LumenSlider) {
        WindowRegressionCapture.main {
            val rect = android.graphics.Rect(0, 0, slider.width, slider.height)
            slider.requestRectangleOnScreen(rect, true)
        }
        WindowRegressionCapture.frame(activity.root)
        SystemClock.sleep(150)
    }

    private fun center(slider: LumenSlider): FloatArray {
        val location = IntArray(2)
        WindowRegressionCapture.main { slider.getLocationOnScreen(location) }
        return floatArrayOf(location[0] + slider.width / 2f, location[1] + slider.height / 2f)
    }

    private fun send(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event) } finally { event.recycle() }
    }

    private fun drag(x: Float, y: Float, dx: Float, dy: Float, holdMs: Long = 0L, steps: Int = 12, during: (() -> Unit)? = null) {
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, x, y)
        if (holdMs > 0) SystemClock.sleep(holdMs)
        for (i in 1..steps) {
            send(down, MotionEvent.ACTION_MOVE, x + dx * i / steps, y + dy * i / steps)
            SystemClock.sleep(12)
        }
        during?.invoke()
        send(down, MotionEvent.ACTION_UP, x + dx, y + dy)
    }

    private fun value(slider: LumenSlider): Float {
        var v = Float.NaN
        WindowRegressionCapture.main { v = slider.value }
        return v
    }

    @Test fun longPressThenDragAdjustsTheSliderInsteadOfTheElasticHold() = launch { _, activity ->
        val slider = activity.sliders.getValue("skin.brightness")
        bringIntoView(activity, slider)
        val start = value(slider)
        val (x, y) = center(slider).let { it[0] to it[1] }
        var draggingMidway = false
        var cardScale = 1f
        // Hold well past the 160ms elastic threshold before moving: the elastic must not capture the slider.
        drag(x, y, slider.width * .25f, 0f, holdMs = 450) {
            WindowRegressionCapture.main {
                draggingMidway = slider.isDragging
                cardScale = (slider.parent.parent as View).scaleX
            }
        }
        assertTrue("slider owns the hold-and-drag", draggingMidway)
        assertEquals("the card around the slider is not deformed", 1f, cardScale, 1e-4f)
        assertTrue("value moved with the finger: $start -> ${value(slider)}", value(slider) > start + 10f)
        SystemClock.sleep(600)
        WindowRegressionCapture.main { assertFalse(slider.isDragging) }
    }

    @Test fun verticalDragStartingOnTheSliderScrollsThePage() = launch { _, activity ->
        val slider = activity.sliders.getValue("fluent.continuous")
        bringIntoView(activity, slider)
        val start = value(slider)
        var scrollBefore = 0
        WindowRegressionCapture.main { scrollBefore = activity.scroll.scrollY }
        val (x, y) = center(slider).let { it[0] to it[1] }
        drag(x, y, 4f, -dpPx(activity, 160f))
        SystemClock.sleep(300)
        var scrollAfter = 0
        WindowRegressionCapture.main { scrollAfter = activity.scroll.scrollY }
        assertTrue("page scrolled: $scrollBefore -> $scrollAfter", scrollAfter > scrollBefore)
        assertEquals("vertical drag never changes the value", start, value(slider), 0f)
    }

    @Test fun tapMapsToTheTappedPositionAndMirrorsInRtl() = launch { _, activity ->
        val slider = activity.sliders.getValue("plain")
        bringIntoView(activity, slider)
        fun tapNearRight() {
            val location = IntArray(2)
            WindowRegressionCapture.main { slider.getLocationOnScreen(location) }
            val x = location[0] + slider.width * .9f
            val y = location[1] + slider.height / 2f
            val down = SystemClock.uptimeMillis()
            send(down, MotionEvent.ACTION_DOWN, x, y)
            send(down, MotionEvent.ACTION_UP, x, y)
            SystemClock.sleep(500)
        }
        tapNearRight()
        assertEquals(9f, value(slider), 0f)
        // Switch through the page's own "从右到左" box so the visible state matches, and always switch back.
        WindowRegressionCapture.main { activity.rtlToggle.isChecked = true }
        try {
            WindowRegressionCapture.frame(activity.root)
            SystemClock.sleep(150)
            WindowRegressionCapture.main { assertEquals(View.LAYOUT_DIRECTION_RTL, slider.layoutDirection) }
            tapNearRight()
            assertEquals("RTL puts the minimum on the right", 1f, value(slider), 0f)
        } finally {
            WindowRegressionCapture.main { activity.rtlToggle.isChecked = false }
        }
        WindowRegressionCapture.main { assertEquals(View.LAYOUT_DIRECTION_LTR, slider.layoutDirection) }
    }

    @Test fun discreteDragLandsExactlyOnAStop() = launch { _, activity ->
        val slider = activity.sliders.getValue("fluent.ticks")
        bringIntoView(activity, slider)
        val changes = ArrayList<Float>()
        WindowRegressionCapture.main { slider.setOnValueChangeListener { _, v, fromUser -> if (fromUser) changes += v } }
        val (x, y) = center(slider).let { it[0] to it[1] }
        drag(x, y, slider.width * .3f, 0f)
        SystemClock.sleep(600)
        val v = value(slider)
        assertTrue("stop value: $v", v in listOf(0f, 25f, 50f, 75f, 100f))
        assertTrue("moved off 50", v > 50f)
        assertTrue("callbacks only report stops: $changes", changes.all { it % 25f == 0f })
    }

    @Test fun keyboardAndAccessibilityActionsStepTheValue() = launch { _, activity ->
        val slider = activity.sliders.getValue("skin.volume")
        WindowRegressionCapture.main {
            val info = AccessibilityNodeInfo.obtain()
            slider.onInitializeAccessibilityNodeInfo(info)
            assertEquals("android.widget.SeekBar", info.className)
            assertNotNull(info.rangeInfo)
            assertEquals(40f, info.rangeInfo.current, 0f)
            assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
            assertEquals(50f, slider.value, 0f)
            val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 83f) }
            assertTrue(slider.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args))
            assertEquals(80f, slider.value, 0f)
            if (Build.VERSION.SDK_INT >= 30) assertEquals("80", slider.stateDescription?.toString())
            slider.onKeyDown(android.view.KeyEvent.KEYCODE_MOVE_END, android.view.KeyEvent(0, android.view.KeyEvent.KEYCODE_MOVE_END))
            assertEquals(100f, slider.value, 0f)
            slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent(0, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
            assertEquals(90f, slider.value, 0f)
            slider.isEnabled = false
            slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
            assertEquals(90f, slider.value, 0f)
        }
    }

    @Test fun disposeRestoresTheThumbItCreatedMaterialsFor() = launch { _, activity ->
        WindowRegressionCapture.main {
            for (key in listOf("fluent.continuous", "mica")) {
                val slider = activity.sliders.getValue(key)
                val thumb = slider.getChildAt(1)
                assertNotNull("$key thumb has a material background", thumb.background)
                slider.dispose()
                assertNull("$key restores the thumb background on dispose", thumb.background)
                // Late calls after dispose are no-ops.
                slider.setValue(.5f)
                slider.updatePalette(activity.lumen.palette)
            }
        }
    }

    @Test fun hdrWindowPressAndReleaseSettle() = launch { _, activity ->
        val slider = activity.sliders.getValue("skin.brightness")
        bringIntoView(activity, slider)
        WindowRegressionCapture.main { activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR }
        SystemClock.sleep(200)
        val (x, y) = center(slider).let { it[0] to it[1] }
        drag(x, y, -slider.width * .2f, 0f, holdMs = 200)
        SystemClock.sleep(700)
        WindowRegressionCapture.main {
            assertFalse(slider.isDragging)
            activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
    }

    @Test fun enlargedPrismLensStaysInsideTheGrooveAtBothEnds() = launch { _, activity ->
        for (key in listOf("prism.capsule", "prism.classic")) {
            val slider = activity.sliders.getValue(key)
            bringIntoView(activity, slider)
            val (x, y) = center(slider).let { it[0] to it[1] }
            for (direction in listOf(1f, -1f)) {
                var left = 0f; var right = 0f; var scale = 1f; var grooveLeft = 0f; var grooveRight = 0f
                // Hold past the end so the lens is fully enlarged before sampling its visual bounds.
                drag(x, y, direction * slider.width * .9f, 0f, during = {
                    SystemClock.sleep(500)
                    WindowRegressionCapture.main {
                        val thumb = slider.getChildAt(1)
                        scale = thumb.scaleX
                        val c = thumb.left + thumb.translationX + thumb.width / 2f
                        left = c - thumb.width / 2f * thumb.scaleX
                        right = c + thumb.width / 2f * thumb.scaleX
                        // The end stretch scales the whole track layer: compare with the groove as drawn.
                        val track = slider.getChildAt(0)
                        grooveLeft = track.pivotX * (1f - track.scaleX)
                        grooveRight = track.pivotX + (track.width - track.pivotX) * track.scaleX
                    }
                })
                assertTrue("$key lens enlarged while held: $scale", scale > 1.2f)
                assertTrue("$key lens left edge $left inside the groove $grooveLeft", left >= grooveLeft - 1f)
                assertTrue("$key lens right edge $right inside the groove $grooveRight", right <= grooveRight + 1f)
                SystemClock.sleep(700)
            }
        }
    }

    @Test fun capsuleFillCoversExactlyTheValue() = launch { scenario, activity ->
        val slider = activity.sliders.getValue("plain")
        bringIntoView(activity, slider)
        WindowRegressionCapture.main { slider.setValue(5f, animate = false); slider.setIcon(null) }
        SystemClock.sleep(200)
        val bitmap = capture(scenario)
        val location = IntArray(2)
        val windowOrigin = IntArray(2)
        var primary = 0
        WindowRegressionCapture.main {
            slider.getLocationInWindow(location)
            activity.window.decorView.getLocationInWindow(windowOrigin)
            primary = activity.lumen.palette.primary
        }
        val y = location[1] + slider.height / 2
        val filled = bitmap.getPixel(location[0] + (slider.width * .25f).toInt(), y)
        val empty = bitmap.getPixel(location[0] + (slider.width * .75f).toInt(), y)
        assertTrue("filled side is the primary fill: ${Integer.toHexString(filled)}", distance(filled, primary) < 70)
        assertTrue("empty side is not the fill: ${Integer.toHexString(empty)}", distance(empty, primary) > 70)
    }

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun dpPx(activity: SliderSandboxActivity, value: Float) = value * activity.resources.displayMetrics.density

    private fun capture(scenario: ActivityScenario<SliderSandboxActivity>): Bitmap {
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
}
