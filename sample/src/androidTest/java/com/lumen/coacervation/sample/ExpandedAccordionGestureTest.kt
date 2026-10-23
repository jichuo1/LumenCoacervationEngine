package com.lumen.coacervation.sample

import android.graphics.Color
import android.graphics.Outline
import android.os.SystemClock
import android.util.TypedValue
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.interaction.ElasticInteractionController
import com.lumen.coacervation.engine.interaction.ElasticTravelPolicy
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.motion.expansion.SectionExpansionController
import com.lumen.coacervation.engine.motion.pager.LumenPagePager
import com.lumen.coacervation.engine.motion.pager.LumenPageScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Window -> pager -> stretch -> scroll -> expanded section -> clickable row dispatch. */
@RunWith(AndroidJUnit4::class)
class ExpandedAccordionGestureTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private class Fixture(val activity: SurfaceSandboxActivity) {
        val pager = LumenPagePager(activity)
        val scrolls = List(2) { LumenPageScrollView(activity, {}, {}) }
        val viewports = ArrayList<LiquidStretchViewport>()
        val row = TextView(activity).apply {
            text = "Hold this expanded entry"; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
            setBackgroundColor(Color.BLUE); isClickable = true
        }
        val inline = FrameLayout(activity).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRect(0, 0, view.width, view.height) }
            }
            clipToOutline = true
            addView(FrameLayout(activity).apply {
                addView(row, FrameLayout.LayoutParams(-1, 60).apply { topMargin = 40 })
                addView(View(activity).apply { setBackgroundColor(Color.RED) },
                    FrameLayout.LayoutParams(-1, 120).apply { topMargin = 240 })
            }, FrameLayout.LayoutParams(-1, 600))
        }
        private val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
        }
        private val header = TextView(activity).apply { text = "Expanded accordion"; isClickable = true }
        private val expansion = SectionExpansionController(card, inline, View(activity),
            activity.resources.displayMetrics.density, notifyPositionChanged = {})
        private val original = checkNotNull(activity.window.callback)
        val controller = ElasticInteractionController(activity.window.decorView).apply {
            travelPolicy = ElasticTravelPolicy.PARENT_BOUNDS
        }
        var clicks = 0
        var cancels = 0

        init {
            activity.binding.close(); activity.session.close()
            row.setOnClickListener { clicks++ }
            card.addView(header, LinearLayout.LayoutParams(-1, 96))
            card.addView(inline, LinearLayout.LayoutParams(-1, 100))
            header.setOnClickListener { expansion.setExpanded(!expansion.expanded, false) }
            scrolls.forEachIndexed { index, scroll ->
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
                    if (index == 0) {
                        addView(View(activity), LinearLayout.LayoutParams(-1, 60))
                        addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                            leftMargin = (activity.resources.displayMetrics.widthPixels / 10).coerceAtMost(80)
                            rightMargin = leftMargin
                        })
                    }
                    addView(TextView(activity).apply { text = "Scrollable native page $index" },
                        LinearLayout.LayoutParams(-1, 4000))
                }
                scroll.addView(content)
                pager.addView(scroll, FrameLayout.LayoutParams(-1, -1))
                viewports += requireNotNull(LiquidStretchViewport.installAround(scroll,
                    { pager.selectedPage == index && pager.isSettled }) { _, _ -> })
            }
            expansion.setExpanded(true, false)
            activity.setContentView(FrameLayout(activity).apply {
                keepScreenOn = true; setBackgroundColor(Color.MAGENTA)
                val screenHeight = activity.resources.displayMetrics.heightPixels
                addView(pager, FrameLayout.LayoutParams(-1, (screenHeight * .6f).toInt().coerceIn(320, 700)).apply {
                    topMargin = (screenHeight * .08f).toInt().coerceAtMost(120)
                })
            })
            activity.window.callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean = controller.dispatch(event) {
                    if (it.actionMasked == MotionEvent.ACTION_CANCEL) cancels++
                    original.dispatchTouchEvent(it)
                }
            }
        }

        fun point(view: View, x: Float = view.width / 2f, y: Float = view.height / 2f): FloatArray {
            val location = IntArray(2); view.getLocationOnScreen(location)
            return floatArrayOf(location[0] + x, location[1] + y)
        }

        fun close() {
            activity.window.callback = original
            controller.dispose(); expansion.cancel(); viewports.forEach { it.finishStretch() }
        }
    }

    @Test fun expandedRowsKeepFourDirectionsWithoutRevealingHiddenSiblings() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var f: Fixture
            scenario.onActivity { f = Fixture(it) }
            WindowRegressionCapture.frame(f.pager)
            try {
                for ((dx, dy) in listOf(-120f to 0f, 120f to 0f, 0f to -120f, 0f to 120f)) {
                    lateinit var point: FloatArray
                    WindowRegressionCapture.main { point = f.point(f.row) }
                    val down = SystemClock.uptimeMillis()
                    send(down, MotionEvent.ACTION_DOWN, point[0], point[1])
                    SystemClock.sleep(190)
                    send(down, MotionEvent.ACTION_MOVE, point[0] + dx, point[1] + dy)
                    WindowRegressionCapture.frame(f.pager)
                    WindowRegressionCapture.main {
                        assertEquals(0, f.pager.selectedPage)
                        if (dx != 0f) assertTrue("Horizontal held entry must follow dx=$dx, actual=${f.row.translationX}", f.row.translationX * dx > 4f * kotlin.math.abs(dx))
                        if (dy != 0f) assertTrue("Vertical held entry must follow dy=$dy, actual=${f.row.translationY}", f.row.translationY * dy > 4f * kotlin.math.abs(dy))
                        assertTrue(f.inline.clipChildren); assertTrue(f.inline.clipToOutline)
                        assertTrue(f.scrolls[0].clipChildren); assertTrue(f.viewports[0].clipChildren)
                    }
                    if (dx != 0f || dy > 0f) {
                        WindowRegressionCapture.copy(f.activity, f.pager) { image ->
                            val at = IntArray(2); val base = IntArray(2)
                            WindowRegressionCapture.main { f.inline.getLocationInWindow(at); f.pager.getLocationInWindow(base) }
                            val left = at[0] - base[0]; val top = at[1] - base[1]
                            val x = when { dx < 0 -> left - 3; dx > 0 -> left + f.inline.width + 3; else -> left + f.inline.width / 2 }
                            val y = if (dy > 0) top + f.inline.height + 3 else top + 70
                            val pixel = image.getPixel(x, y)
                            assertTrue("Only the held row crosses the old inline clip", Color.blue(pixel) > 200 &&
                                Color.blue(pixel) > Color.red(pixel) + 30 && Color.blue(pixel) > Color.green(pixel) + 30)
                            assertEquals("The hidden red sibling remains clipped", Color.WHITE,
                                image.getPixel(left + f.inline.width / 2, top + f.inline.height + 30))
                        }
                    }
                    send(down, MotionEvent.ACTION_UP, point[0] + dx, point[1] + dy)
                    WindowRegressionCapture.main { f.controller.clear(); assertEquals(0, f.clicks) }
                    WindowRegressionCapture.frame(f.pager)
                }
                WindowRegressionCapture.main { assertEquals("One native cancellation per held gesture", 4, f.cancels) }
            } finally { WindowRegressionCapture.main { f.close() } }
        }
    }

    @Test fun expandedEntryStillAllowsBothPageSwipesAndNativeVerticalScrolling() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var f: Fixture
            scenario.onActivity { f = Fixture(it) }
            WindowRegressionCapture.frame(f.pager)
            try {
                var entryY = 0f
                WindowRegressionCapture.main {
                    entryY = (f.point(f.row)[1] - f.point(f.pager, 0f, 0f)[1]) / f.pager.height
                }
                swipe(f, .5f, entryY, .08f, entryY)
                WindowRegressionCapture.main { assertEquals(1, f.pager.selectedPage); f.pager.selectPage(1, false) }
                WindowRegressionCapture.frame(f.pager)
                swipe(f, .2f, .35f, .85f, .35f)
                WindowRegressionCapture.main { assertEquals(0, f.pager.selectedPage); f.pager.selectPage(0, false) }
                WindowRegressionCapture.frame(f.pager)
                swipe(f, .5f, .75f, .5f, .25f)
                WindowRegressionCapture.main {
                    assertEquals(0, f.pager.selectedPage)
                    assertTrue("Vertical drag must remain a native scroll", f.scrolls[0].scrollY > 0)
                    assertEquals(0, f.clicks)
                }
            } finally { WindowRegressionCapture.main { f.close() } }
        }
    }

    @Test fun leavingThePageReleasesTheHeldTransformAndCannotReviveItsOverflow() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var f: Fixture
            scenario.onActivity { f = Fixture(it) }
            WindowRegressionCapture.frame(f.pager)
            try {
                lateinit var point: FloatArray
                WindowRegressionCapture.main { point = f.point(f.row) }
                val down = SystemClock.uptimeMillis()
                send(down, MotionEvent.ACTION_DOWN, point[0], point[1])
                SystemClock.sleep(190)
                send(down, MotionEvent.ACTION_MOVE, point[0] - 120, point[1])
                WindowRegressionCapture.main { assertTrue(f.row.translationX < -4f); f.pager.selectPage(1, false) }
                WindowRegressionCapture.frame(f.pager)
                WindowRegressionCapture.frame(f.pager)
                WindowRegressionCapture.main {
                    assertEquals(0f, f.row.translationX, 0f)
                    assertEquals(1f, f.row.scaleX, 0f)
                    assertEquals(1, f.pager.selectedPage)
                }
                send(down, MotionEvent.ACTION_UP, point[0] - 120, point[1])
                WindowRegressionCapture.main { f.pager.selectPage(0, false); assertEquals(0, f.clicks) }
                WindowRegressionCapture.frame(f.pager)
                WindowRegressionCapture.main { assertEquals(0f, f.row.translationX, 0f); assertTrue(f.inline.clipChildren) }
            } finally { WindowRegressionCapture.main { f.close() } }
        }
    }

    private fun swipe(f: Fixture, x0: Float, y0: Float, x1: Float, y1: Float) {
        lateinit var from: FloatArray
        lateinit var to: FloatArray
        WindowRegressionCapture.main {
            from = f.point(f.pager, f.pager.width * x0, f.pager.height * y0)
            to = f.point(f.pager, f.pager.width * x1, f.pager.height * y1)
        }
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, from[0], from[1])
        for (step in 1..8) {
            send(down, MotionEvent.ACTION_MOVE, from[0] + (to[0] - from[0]) * step / 8, from[1] + (to[1] - from[1]) * step / 8)
        }
        send(down, MotionEvent.ACTION_UP, to[0], to[1])
    }

    private fun send(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { InstrumentationRegistry.getInstrumentation().sendPointerSync(event) } finally { event.recycle() }
    }
}
