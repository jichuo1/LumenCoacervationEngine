package com.lumen.coacervation.sample

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.controls.LumenControls
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.motion.pager.LumenPagePager
import com.lumen.coacervation.engine.motion.pager.LumenPageScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Attached Views: a nested-scroll DOWN is not evidence of a vertical drag. */
@RunWith(AndroidJUnit4::class)
class PageGestureOwnershipTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private class Fixture(activity: SurfaceSandboxActivity) {
        val pager = LumenPagePager(activity)
        val toggle = SwitchCompat(activity).apply { text = "Toggle" }
        val scrolls = List(2) { LumenPageScrollView(activity, {}, {}) }
        val viewports = ArrayList<LiquidStretchViewport>()
        val root = FrameLayout(activity).apply { addView(pager, FrameLayout.LayoutParams(-1, -1)) }
        var peak = 0f
        init {
            activity.session.close()
            pager.switchParts = { LumenControls.switchParts(it) ?: LumenPagePager.frameworkSwitchParts(it) }
            scrolls.forEachIndexed { index, scroll ->
                val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                if (index == 0) content.addView(toggle, LinearLayout.LayoutParams(-1, 240))
                content.addView(TextView(activity).apply { text = "Scrollable content" }, LinearLayout.LayoutParams(-1, 12000))
                scroll.addView(content)
                pager.addView(scroll, FrameLayout.LayoutParams(-1, -1))
                viewports += requireNotNull(LiquidStretchViewport.installAround(scroll,
                    { pager.isSettled && pager.selectedPage == index }) { distance, _ -> peak = maxOf(peak, distance) })
            }
            activity.setContentView(root)
        }
    }

    @Test fun wrappedPagesSwipeBothWaysAndStillScrollVertically() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var f: Fixture
            scenario.onActivity { f = Fixture(it) }
            frames(scenario, f.root)
            scenario.onActivity {
                swipe(f.pager, .8f, .45f, .15f, .45f)
                assertEquals(1, f.pager.selectedPage)
                f.pager.selectPage(1, false)
                swipe(f.pager, .2f, .45f, .85f, .45f)
                assertEquals(0, f.pager.selectedPage)
                f.pager.selectPage(0, false)
                swipe(f.pager, .5f, .65f, .5f, .35f)
                assertEquals(0, f.pager.selectedPage)
                assertTrue("Vertical drag must still scroll content", f.scrolls[0].scrollY > 0)
            }
        }
    }

    @Test fun aSwitchTapAndThumbDragStayWithTheSwitch() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var f: Fixture
            scenario.onActivity { f = Fixture(it) }
            frames(scenario, f.root)
            scenario.onActivity {
                val bounds = f.toggle.thumbDrawable.bounds
                val x = f.toggle.left + bounds.exactCenterX()
                val y = f.toggle.top + bounds.exactCenterY()
                val time = SystemClock.uptimeMillis()
                send(f.pager, time, 0, MotionEvent.ACTION_DOWN, x, y)
                send(f.pager, time, 1, MotionEvent.ACTION_UP, x, y)
            }
            // View posts PerformClick from ACTION_UP; inspect the result on the next UI turn.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue("Tap must reach the switch", f.toggle.isChecked)
                val bounds = f.toggle.thumbDrawable.bounds
                val x = f.toggle.left + bounds.exactCenterX()
                val y = f.toggle.top + bounds.exactCenterY()
                val next = SystemClock.uptimeMillis()
                send(f.pager, next, 0, MotionEvent.ACTION_DOWN, x, y)
                repeat(8) { step -> send(f.pager, next, step + 1, MotionEvent.ACTION_MOVE, x - (step + 1) * 50f, y) }
                send(f.pager, next, 9, MotionEvent.ACTION_UP, x - 400f, y)
                assertEquals("The protected thumb must not start a page swipe", 0, f.pager.selectedPage)
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun reboundCanBeCaughtWithoutTogglingTheContentOrChangingPage() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var f: Fixture
            var down = 0L
            scenario.onActivity { f = Fixture(it) }
            frames(scenario, f.root)
            scenario.onActivity {
                down = SystemClock.uptimeMillis()
                send(f.pager, down, 0, MotionEvent.ACTION_DOWN, f.pager.width * .5f, f.pager.height * .35f)
                repeat(10) { step -> send(f.pager, down, step + 1, MotionEvent.ACTION_MOVE,
                    f.pager.width * .5f, f.pager.height * (.35f + .025f * (step + 1))) }
            }
            frames(scenario, f.root)
            scenario.onActivity {
                assertTrue("A real edge pull must create native stretch", f.peak > 0f)
                send(f.pager, down, 12, MotionEvent.ACTION_UP, f.pager.width * .5f, f.pager.height * .6f)
                val thumb = f.toggle.thumbDrawable.bounds
                val x = thumb.exactCenterX()
                val y = thumb.exactCenterY()
                val next = SystemClock.uptimeMillis()
                send(f.pager, next, 0, MotionEvent.ACTION_DOWN, x, y)
                assertTrue("DOWN must catch the active rebound", f.viewports[0].claimsCurrentGesture)
                send(f.pager, next, 1, MotionEvent.ACTION_UP, x, y)
                assertFalse("A caught rebound must not click through", f.toggle.isChecked)
                assertEquals(0, f.pager.selectedPage)
            }
        }
    }

    @Test fun productionSamplePagesAcceptBothSwipeDirections() {
        ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
            scenario.onActivity { it.pager.selectPage(0, false) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                swipe(it.pager, .8f, .45f, .15f, .45f)
                assertEquals(1, it.pager.selectedPage)
                it.pager.selectPage(1, false)
                swipe(it.pager, .2f, .3f, .85f, .3f)
                assertEquals(0, it.pager.selectedPage)
                it.pager.selectPage(0, false)
            }
        }
    }

    private fun swipe(view: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        send(view, down, 0, MotionEvent.ACTION_DOWN, view.width * x0, view.height * y0)
        for (step in 1..12) send(view, down, step, MotionEvent.ACTION_MOVE,
            view.width * (x0 + (x1 - x0) * step / 12), view.height * (y0 + (y1 - y0) * step / 12))
        send(view, down, 13, MotionEvent.ACTION_UP, view.width * x1, view.height * y1)
    }

    private fun send(view: View, down: Long, step: Int, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, down + step * 16L, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun <T : android.app.Activity> frames(scenario: ActivityScenario<T>, view: View) {
        val done = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { view.postOnAnimation { view.postOnAnimation { done.countDown() } }; view.invalidate() }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
