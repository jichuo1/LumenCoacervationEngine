package com.lumen.coacervation.sample

import android.app.Dialog
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.liquid.LiquidStretchEdge
import com.lumen.coacervation.engine.liquid.LiquidStretchSampling
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.liquid.LiquidStretchWindowRegistry
import com.lumen.coacervation.engine.motion.modal.LumenPanelScroll
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PanelInlineScrollTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    private class Fixture(activity: SurfaceSandboxActivity) {
        val registry = LiquidStretchWindowRegistry()
        val row = View(activity).apply { minimumHeight = 1200 }
        val inner = ScrollView(activity).apply { addView(row, FrameLayout.LayoutParams(-1, 1200)) }
        val horizontal = HorizontalScrollView(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(inner, LinearLayout.LayoutParams(240, 180))
            addView(horizontal, LinearLayout.LayoutParams(240, 50))
            addView(View(activity), LinearLayout.LayoutParams(240, 1500))
        }
        val outer = NestedScrollView(activity).apply { addView(body) }
        val root = FrameLayout(activity).apply { addView(outer, FrameLayout.LayoutParams(260, 420)) }
        lateinit var outerViewport: LiquidStretchViewport
        lateinit var innerViewport: LiquidStretchViewport
        var installations = 0
        var innerStretch: CountDownLatch? = null
        var outerPeak = 0f
        var innerPeak = 0f
        var idleNotifications = 0

        init {
            activity.session.close()
            activity.setContentView(root)
            prepare()
        }

        fun prepare() = LumenPanelScroll.prepare(root) { target ->
            installations++
            LiquidStretchViewport.installAround(target, { true }) { distance, edge ->
                if (distance == 0f) idleNotifications++
                val direction = when (edge) {
                    LiquidStretchEdge.TOP -> -1f
                    LiquidStretchEdge.BOTTOM -> 1f
                    LiquidStretchEdge.NONE -> 0f
                }
                registry.updateForScrollHost(target, distance, 1f + distance, direction)
                if (target === inner) {
                    innerPeak = maxOf(innerPeak, distance)
                    if (distance > 0f) innerStretch?.countDown()
                } else outerPeak = maxOf(outerPeak, distance)
            }?.also { viewport ->
                if (target === outer) outerViewport = viewport else innerViewport = viewport
            }
        }
    }

    @Test fun preparationVisitsInlineScrollsInsideInstalledAndExistingViewports() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fixture = Fixture(activity)
                fixture.prepare()
                fixture.prepare()
                assertEquals(2, fixture.installations)
                assertSame(fixture.outerViewport, fixture.outer.parent)
                assertSame(fixture.innerViewport, fixture.inner.parent)
                assertSame(fixture.body, fixture.horizontal.parent)
                assertTrue(fixture.inner.isNestedScrollingEnabled)
                assertEquals(View.OVER_SCROLL_NEVER, fixture.inner.overScrollMode)
                fixture.body.addView(NestedScrollView(activity).apply { addView(View(activity)) })
                fixture.prepare()
                assertEquals("Preparing an existing viewport still discovers new inline content", 3, fixture.installations)
            }
        }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun platformFlingReboundsInTheNearestViewportWithoutMovingTheOuterScroll() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            val rebound = CountDownLatch(1)
            scenario.onActivity { fixture = Fixture(it); fixture.innerStretch = rebound }
            frame(scenario, fixture.root)
            scenario.onActivity {
                fixture.inner.scrollTo(0, 780)
                val rootOrigin = IntArray(2)
                val innerOrigin = IntArray(2)
                fixture.root.getLocationInWindow(rootOrigin)
                fixture.inner.getLocationInWindow(innerOrigin)
                val x = innerOrigin[0] - rootOrigin[0] + 120f
                val y = innerOrigin[1] - rootOrigin[1] + 150f
                val down = SystemClock.uptimeMillis()
                for ((offset, position) in arrayOf(0L to y, 16L to y - 25f, 32L to y - 80f, 40L to y - 100f)) {
                    val action = if (offset == 0L) MotionEvent.ACTION_DOWN else if (offset == 40L) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    val event = MotionEvent.obtain(down, down + offset, action, x, position, 0)
                    try { assertTrue(fixture.root.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
            }
            assertTrue("The real platform touch fling must hand its remaining velocity to engine stretch", rebound.await(5, TimeUnit.SECONDS))
            scenario.onActivity {
                assertTrue(fixture.innerPeak > 0f)
                assertEquals(0f, fixture.outerPeak, 0f)
                assertEquals("The inline scroll owns its gesture", 0, fixture.outer.scrollY)
                assertFalse(fixture.inner.canScrollVertically(1))
            }
            SystemClock.sleep(1000)
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertEquals(0f, fixture.innerViewport.samplingOverscroll, 0f)
                assertEquals(0f, fixture.registry.stateFor(fixture.inner)!!.distance, 0f)
            }
        }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun idleAndFinishingInlineViewportsPreserveAnActiveOuterRebound() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it) }
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertEquals("Idle viewports must not publish redundant zero refreshes", 0, fixture.idleNotifications)
                fixture.outerViewport.onNestedScroll(fixture.outer, 0, 0, 0, -80, ViewCompat.TYPE_TOUCH, IntArray(2))
            }
            frame(scenario, fixture.root)
            var outerDistance = 0f
            scenario.onActivity {
                outerDistance = fixture.registry.stateFor(fixture.outer)!!.distance
                assertTrue(outerDistance > 0f)
                assertEquals(0f, fixture.innerViewport.samplingOverscroll, 0f)
                val coordinates = FloatArray(3)
                LiquidStretchSampling().resolve(fixture.row, fixture.root, coordinates)
                assertEquals("An idle nearest viewport must not hide the active outer transform",
                    fixture.outerViewport.samplingOverscroll, coordinates[2], 0f)
                fixture.innerViewport.finishStretch()
                fixture.root.invalidate()
            }
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertEquals(outerDistance, fixture.registry.stateFor(fixture.outer)!!.distance, .0001f)
                fixture.outerViewport.finishStretch()
                fixture.innerViewport.onNestedScroll(fixture.inner, 0, 0, 0, 30, ViewCompat.TYPE_TOUCH, IntArray(2))
            }
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertTrue(fixture.innerViewport.samplingOverscroll < 0f)
                fixture.outerViewport.onNestedScroll(fixture.outer, 0, 0, 0, -80, ViewCompat.TYPE_TOUCH, IntArray(2))
            }
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertTrue(fixture.outerViewport.samplingOverscroll > 0f)
                assertEquals("One ancestor chain must not draw two native warps", 0f, fixture.innerViewport.samplingOverscroll, 0f)
                fixture.innerViewport.finishStretch()
                assertEquals("Ending an inner decoration preserves the outer contribution", outerDistance,
                    fixture.registry.stateFor(fixture.outer)!!.distance, .0001f)
                fixture.outerViewport.finishStretch()
                assertEquals(0f, fixture.registry.stateFor(fixture.outer)!!.distance, 0f)
            }
        }
    }

    @Test fun concurrentScrollOwnersStayWindowIsolatedAndDetachedZeroReturnsToItsOwner() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.session.close()
                val main = View(activity)
                val sibling = View(activity)
                activity.setContentView(FrameLayout(activity).apply { addView(main); addView(sibling) })
                val popup = View(activity)
                val popupRoot = FrameLayout(activity).apply { addView(popup) }
                val dialog = Dialog(activity)
                try {
                    dialog.setContentView(popupRoot)
                    dialog.show()
                    val registry = LiquidStretchWindowRegistry()
                    val mainWindow = main.rootView
                    val popupWindow = popup.rootView
                    assertNotSame(mainWindow, popupWindow)
                    val mainUpdate = registry.updateForScrollHost(main, .2f, 1.5f, -1f)!!
                    val popupUpdate = registry.updateForScrollHost(popup, .3f, 1.8f, 1f)!!
                    assertSame(mainWindow, mainUpdate.window)
                    assertSame(popupWindow, popupUpdate.window)
                    assertEquals(.2f, registry.stateFor(main)!!.distance, 0f)
                    val siblingsUpdate = registry.updateForScrollHost(sibling, .4f, 1.9f, 1f)!!
                    assertSame(mainUpdate, siblingsUpdate)
                    assertEquals(.4f, registry.stateFor(main)!!.distance, 0f)
                    registry.updateForScrollHost(main, 0f, 1f, 0f)
                    assertEquals("Finishing the first owner keeps the sibling", .4f, registry.stateFor(sibling)!!.distance, 0f)
                    registry.updateForScrollHost(main, .2f, 1.5f, -1f)
                    registry.updateForScrollHost(sibling, 0f, 1f, 0f)
                    assertEquals("Finishing the sibling restores the first owner", .2f, registry.stateFor(main)!!.distance, 0f)
                    popupRoot.removeView(popup)
                    assertFalse(popup.isAttachedToWindow)
                    val terminal = registry.updateForScrollHost(popup, .3f, 1.8f, 1f)!!
                    assertSame(popupUpdate, terminal)
                    assertSame(popupWindow, terminal.window)
                    assertEquals(0f, terminal.state.distance, 0f)
                    assertNull("A late detached update must not revive the old window",
                        registry.updateForScrollHost(popup, .3f, 1.8f, 1f))
                    assertEquals(.2f, registry.stateFor(main)!!.distance, 0f)
                    registry.clear()
                    assertNull(registry.stateFor(main))
                } finally {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun frame(scenario: ActivityScenario<SurfaceSandboxActivity>, root: View) {
        val committed = CountDownLatch(1)
        scenario.onActivity {
            root.postOnAnimation { root.postOnAnimation { committed.countDown() } }
            root.postInvalidateOnAnimation()
        }
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
